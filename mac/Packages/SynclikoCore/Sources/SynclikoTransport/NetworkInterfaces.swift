import Darwin
import Foundation
import Synchronization
import SynclikoProtocol

/// Lists the addresses peers can reach this Mac on: LAN IPv4 addresses and the Tailscale 100.64.0.0/10 address.
public enum NetworkInterfaces {
    public struct Address: Sendable, Equatable {
        public var interface: String
        public var ip: String
        public var isTailscale: Bool
    }

    public static func ipv4Addresses() -> [Address] {
        var result: [Address] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            let flags = Int32(entry.ifa_flags)
            guard let addr = entry.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0
            else { continue }
            var sin = sockaddr_in()
            memcpy(&sin, addr, MemoryLayout<sockaddr_in>.size)
            let raw = UInt32(bigEndian: sin.sin_addr.s_addr)
            var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var inAddr = sin.sin_addr
            inet_ntop(AF_INET, &inAddr, &buffer, socklen_t(INET_ADDRSTRLEN))
            let name = String(cString: entry.ifa_name)
            let ip = String(decoding: buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            let isTailscale = (raw & 0xFFC0_0000) == 0x6440_0000 // 100.64.0.0/10
            let isLinkLocal = (raw & 0xFFFF_0000) == 0xA9FE_0000 // 169.254.0.0/16
            guard !isLinkLocal else { continue }
            // Keep physical interfaces (Wi-Fi/Ethernet) and Tailscale; skip VM bridges and other VPN tunnels.
            guard isTailscale || name.hasPrefix("en") else { continue }
            result.append(Address(interface: name, ip: ip, isTailscale: isTailscale))
        }
        return result
    }

    public static func endpoints(port: UInt16) -> [Endpoint] {
        var seen = Set<String>()
        let all = ipv4Addresses()
            .sorted { !$0.isTailscale && $1.isTailscale }
            .filter { seen.insert($0.ip).inserted }
            .map { Endpoint(host: $0.ip, port: Int(port), kind: $0.isTailscale ? .tailscale : .lan) }
        return all + cachedTailscaleName.withLock { $0 }.map { [Endpoint(host: $0, port: Int(port), kind: .tailscale)] }.orEmpty
    }

    private static let cachedTailscaleName = Mutex<String?>(nil)
    private static let refreshing = Mutex(false)

    /// Refreshes the cached MagicDNS name in the background (the `tailscale` CLI can hang; never call
    /// it on a latency-sensitive queue). Safe to call often.
    public static func refreshTailscaleName() {
        guard refreshing.withLock({ busy in
            if busy { return false }
            busy = true
            return true
        }) else { return }
        DispatchQueue.global(qos: .utility).async {
            let name = tailscaleDNSName(timeout: 2)
            cachedTailscaleName.withLock { $0 = name }
            refreshing.withLock { $0 = false }
        }
    }

    /// MagicDNS name of this Mac, if the Tailscale app is installed and running.
    public static func tailscaleDNSName(timeout: TimeInterval = 2) -> String? {
        let candidates = [
            "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
            "/opt/homebrew/bin/tailscale",
            "/usr/local/bin/tailscale",
        ]
        guard let binary = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else { return nil }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: binary)
        process.arguments = ["status", "--self", "--peers=false", "--json"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return nil }
        let killer = DispatchWorkItem { if process.isRunning { process.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: killer)
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        killer.cancel()
        guard process.terminationStatus == 0,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let me = json["Self"] as? [String: Any],
              let dns = me["DNSName"] as? String, !dns.isEmpty
        else { return nil }
        return dns.hasSuffix(".") ? String(dns.dropLast()) : dns
    }
}

private extension Optional where Wrapped == [Endpoint] {
    var orEmpty: [Endpoint] { self ?? [] }
}
