import CryptoKit
import Foundation

/// Six-digit code both devices show during pairing so the user can check they talk to each other
/// (the device name in a pairing request is chosen by the requester). Derived from both certificate
/// fingerprints: SHA-256(macFingerprint ‖ phoneFingerprint), first 4 bytes big-endian, mod 1 000 000.
public enum PairingCode {
    public static func make(macFingerprint: Data, phoneFingerprint: Data) -> String {
        let digest = Array(SHA256.hash(data: macFingerprint + phoneFingerprint))
        let value = (UInt32(digest[0]) << 24 | UInt32(digest[1]) << 16 | UInt32(digest[2]) << 8 | UInt32(digest[3])) % 1_000_000
        let digits = String(format: "%06u", value)
        return digits.prefix(3) + " " + digits.suffix(3)
    }
}

/// Contents of the pairing QR code (PROTOCOL.md §5).
public struct PairingInvite: Sendable, Equatable {
    public var version: Int
    public var deviceId: String
    public var deviceName: String
    public var fingerprint: Data
    public var token: Data
    public var port: Int
    public var hosts: [String]

    public init(version: Int = SynclikoProtocol.version, deviceId: String, deviceName: String, fingerprint: Data, token: Data, port: Int, hosts: [String]) {
        self.version = version
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.fingerprint = fingerprint
        self.token = token
        self.port = port
        self.hosts = hosts
    }

    public var url: URL {
        var c = URLComponents()
        c.scheme = "syncliko"
        c.host = "pair"
        c.queryItems = [
            .init(name: "v", value: String(version)),
            .init(name: "id", value: deviceId),
            .init(name: "n", value: deviceName),
            .init(name: "fp", value: fingerprint.base64URLEncoded),
            .init(name: "t", value: token.base64URLEncoded),
            .init(name: "p", value: String(port)),
            .init(name: "h", value: hosts.joined(separator: ",")),
        ]
        // URLComponents leaves "+" unescaped, which some parsers read as a space.
        c.percentEncodedQuery = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return c.url!
    }

    public init?(url: URL) {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false),
              c.scheme == "syncliko", c.host == "pair" else { return nil }
        var q: [String: String] = [:]
        for item in c.queryItems ?? [] { q[item.name] = item.value }
        guard let v = q["v"].flatMap(Int.init), let id = q["id"], let name = q["n"],
              let fp = q["fp"].flatMap(Data.init(base64URLEncoded:)), fp.count == 32,
              let token = q["t"].flatMap(Data.init(base64URLEncoded:)), !token.isEmpty,
              let port = q["p"].flatMap(Int.init), (1...65535).contains(port)
        else { return nil }
        let hosts = (q["h"] ?? "").split(separator: ",").map(String.init).filter { !$0.isEmpty }
        self.init(version: v, deviceId: id, deviceName: name, fingerprint: fp, token: token, port: port, hosts: hosts)
    }
}

extension Data {
    public var base64URLEncoded: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public init?(base64URLEncoded string: String) {
        var s = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        self.init(base64Encoded: s)
    }

    public var hexString: String { map { String(format: "%02x", $0) }.joined() }

    public init?(hexString: String) {
        guard hexString.count.isMultiple(of: 2) else { return nil }
        var data = Data(capacity: hexString.count / 2)
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        self = data
    }

    /// Constant-time equality, for secrets such as pairing tokens.
    public func constantTimeEquals(_ other: Data) -> Bool {
        guard count == other.count else { return false }
        var diff: UInt8 = 0
        for (a, b) in zip(self, other) { diff |= a ^ b }
        return diff == 0
    }
}
