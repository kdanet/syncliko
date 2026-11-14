import Foundation

/// Canonical messages used to produce `protocol/vectors.json`, shared with the Android implementation.
public enum TestVectors {
    public static let clipId = "6F9619FF-8B86-D011-B42D-00C04FC964FF"
    public static let createdAt: Int64 = 1_763_078_400_000

    public static var messages: [(name: String, message: Message)] {
        let text = Data("Привет, Syncliko! 👋".utf8)
        return [
            ("hello", .hello(Hello(deviceId: "mac-1", deviceName: "MacBook Pro", platform: .macos, appVersion: "0.1.0"))),
            ("pairRequest", .pairRequest(PairRequest(token: "AAECAwQFBgcICQoLDA0ODw", deviceId: "android-1", deviceName: "Galaxy S25", platform: .android))),
            ("pairResult", .pairResult(PairResult(accepted: true, deviceId: "mac-1", deviceName: "MacBook Pro"))),
            ("clipText", .clip(ClipHeader(id: clipId, origin: "mac-1", createdAt: createdAt, kind: .text, mime: "text/plain;charset=utf-8", size: text.count, sha256: text.sha256Hex, sensitive: false), text)),
            ("clipChunk", .clipChunk(ClipChunk(id: clipId, offset: 262_144), Data([0x61, 0x62, 0x63]))),
            ("ack", .ack(Ack(id: clipId))),
            ("ping", .ping(Ping(t: createdAt))),
            ("pong", .pong(Ping(t: createdAt))),
            ("requestLatest", .requestLatest),
            ("endpoints", .endpoints(Endpoints(endpoints: [
                Endpoint(host: "192.168.1.20", port: 47823, kind: .lan),
                Endpoint(host: "100.101.102.103", port: 47823, kind: .tailscale),
            ]))),
            ("error", .error(ProtocolErrorMessage(code: .unknownType, message: "type 0x42"))),
        ]
    }

    public static let pairingInvite = PairingInvite(
        deviceId: "mac-1",
        deviceName: "Danil's MacBook Pro",
        fingerprint: Data((0..<32).map { UInt8($0) }),
        token: Data((0..<16).map { UInt8(0xF0 + $0 % 16) }),
        port: 47823,
        hosts: ["192.168.1.20", "100.101.102.103", "macbook.tail1234.ts.net"]
    )

    /// JSON document written to `protocol/vectors.json`.
    public static func json() throws -> Data {
        var frames: [[String: Any]] = []
        for (name, message) in messages {
            let frame = SynclikoProtocol.encode(message)
            frames.append([
                "name": name,
                "type": Int(frame.rawType),
                "hex": frame.encoded().hexString,
                "header": try JSONSerialization.jsonObject(with: frame.header),
                "payloadHex": frame.payload.hexString,
            ])
        }
        let invite = pairingInvite
        let doc: [String: Any] = [
            "protocolVersion": SynclikoProtocol.version,
            "frames": frames,
            "pairingInvites": [[
                "url": invite.url.absoluteString,
                "deviceId": invite.deviceId,
                "deviceName": invite.deviceName,
                "fingerprintHex": invite.fingerprint.hexString,
                "tokenHex": invite.token.hexString,
                "port": invite.port,
                "hosts": invite.hosts,
            ]],
        ]
        return try JSONSerialization.data(withJSONObject: doc, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
    }
}
