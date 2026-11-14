// syncliko-probe — a stand-in for the Android client (and a tiny server) for manual testing.
//
//   syncliko-probe serve [--port N]       run a server that auto-accepts pairing, prints the invite URL
//   syncliko-probe pair "<syncliko://…>"  pair with a Mac and stay connected
//   syncliko-probe connect                reconnect to the last paired Mac
//
// While connected, every line typed on stdin is sent as a text clip; received clips are printed.

import CryptoKit
import Foundation
import SynclikoProtocol
import SynclikoTransport

setvbuf(stdout, nil, _IOLBF, 0)
let stateDir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".syncliko-probe")
try? FileManager.default.createDirectory(at: stateDir, withIntermediateDirectories: true)

func log(_ s: String) {
    let t = Date().formatted(.dateTime.hour().minute().second().secondFraction(.fractional(3)))
    FileHandle.standardError.write(Data("[\(t)] \(s)\n".utf8))
}

func loadIdentity(_ name: String) throws -> TLSIdentity {
    let keyURL = stateDir.appendingPathComponent("\(name).key"), certURL = stateDir.appendingPathComponent("\(name).der")
    if let key = try? Data(contentsOf: keyURL), let cert = try? Data(contentsOf: certURL) {
        return TLSIdentity(privateKey: try P256.Signing.PrivateKey(rawRepresentation: key), certificateDER: cert)
    }
    let identity = try TLSIdentity.generate(commonName: "syncliko-probe \(name)")
    try identity.privateKey.rawRepresentation.write(to: keyURL)
    try identity.certificateDER.write(to: certURL)
    // PEM copies for openssl interop tests.
    try identity.privateKey.pemRepresentation.write(to: stateDir.appendingPathComponent("\(name).key.pem"), atomically: true, encoding: .utf8)
    let pem = "-----BEGIN CERTIFICATE-----\n" + identity.certificateDER.base64EncodedString(options: .lineLength64Characters) + "\n-----END CERTIFICATE-----\n"
    try pem.write(to: stateDir.appendingPathComponent("\(name).crt.pem"), atomically: true, encoding: .utf8)
    return identity
}

func readStdinLines(_ body: @escaping @Sendable (String) -> Void) {
    Thread.detachNewThread {
        while let line = readLine() { body(line) }
    }
}

func describe(_ clip: Clip) -> String {
    switch clip.kind {
    case .image: "image \(clip.header.mime) \(clip.content.count) bytes"
    default: "\(clip.kind.rawValue): \(clip.text ?? "?")"
    }
}

let args = Array(CommandLine.arguments.dropFirst())
let deviceId = "probe-\(Host.current().localizedName ?? "host")"

switch args.first {
case "serve":
    let identity = try loadIdentity("server")
    let port = args.firstIndex(of: "--port").map { UInt16(args[$0 + 1])! } ?? SynclikoProtocol.defaultPort
    let hello = Hello(deviceId: deviceId, deviceName: "Probe Server", platform: .macos, appVersion: "probe")
    nonisolated(unsafe) var serverRef: SyncServer?
    let server = SyncServer(configuration: .init(identity: identity, hello: hello, preferredPort: port, bonjourName: "Syncliko Probe")) { event in
        switch event {
        case .listening(let p):
            let token = serverRef!.openPairingWindow(duration: 3600)
            let hosts = NetworkInterfaces.endpoints(port: p).map(\.host) + ["127.0.0.1"]
            let invite = PairingInvite(deviceId: deviceId, deviceName: "Probe Server", fingerprint: identity.fingerprint, token: token, port: Int(p), hosts: hosts)
            log("listening on \(p), fingerprint \(identity.fingerprint.hexString)")
            print(invite.url.absoluteString)
        case .pairingRequested(let c):
            log("pairing request from \(c.request.deviceName) — auto-accepting")
            serverRef?.resolvePairing(c.id, accept: true)
        case .clipReceived(let clip, let peer): log("← \(peer.hello.deviceName): \(describe(clip))")
        default: log("\(event)")
        }
    }
    serverRef = server
    server.start(trustedFingerprints: [])
    readStdinLines { line in server.broadcast(Clip.text(line, origin: deviceId)) }
    dispatchMain()

case "pair", "connect":
    let identity = try loadIdentity("client")
    let inviteURL = stateDir.appendingPathComponent("invite.txt")
    let urlString = args.first == "pair" ? args[1] : try String(contentsOf: inviteURL, encoding: .utf8)
    guard let invite = PairingInvite(url: URL(string: urlString)!) else { fatalError("bad invite URL") }
    try urlString.write(to: inviteURL, atomically: true, encoding: .utf8)
    let host = args.firstIndex(of: "--host").map { args[$0 + 1] } ?? invite.hosts.first ?? "127.0.0.1"
    let hello = Hello(deviceId: deviceId, deviceName: "Probe Phone", platform: .android, appVersion: "probe")
    let client = SyncClient(identity: identity, hello: hello)
    let mode: SyncClient.Mode = args.first == "pair" ? .pairing(token: invite.token) : .normal
    log("connecting to \(host):\(invite.port)")
    let started = Date()
    try client.connect(host: host, port: UInt16(invite.port), serverFingerprint: invite.fingerprint, mode: mode) { event in
        switch event {
        case .secured: log("TLS up in \(Int(Date().timeIntervalSince(started) * 1000)) ms")
        case .clip(let clip):
            let latency = Int(Date().timeIntervalSince(clip.createdAt) * 1000)
            log("← \(describe(clip)) (\(latency) ms after copy)")
        case .closed(let reason):
            log("closed: \(reason ?? "-")")
            exit(1)
        default: log("\(event)")
        }
    }
    readStdinLines { line in client.send(Clip.text(line, origin: deviceId)) }
    dispatchMain()

default:
    print("usage: syncliko-probe serve [--port N] | pair <url> [--host H] | connect [--host H]")
    exit(2)
}
