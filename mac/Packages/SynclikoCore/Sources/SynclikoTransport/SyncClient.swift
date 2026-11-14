import Foundation
import Network
import SynclikoProtocol

/// Reference client (the role the Android app plays). Used by tests and `syncliko-probe`.
public final class SyncClient: @unchecked Sendable {
    public enum Mode: Sendable {
        case normal
        case pairing(token: Data)
    }

    public let identity: TLSIdentity
    public let hello: Hello
    public let queue = DispatchQueue(label: "syncliko.client")
    public private(set) var session: SyncSession?

    public init(identity: TLSIdentity, hello: Hello) {
        self.identity = identity
        self.hello = hello
    }

    /// Connects to `host:port`, pinning the server certificate to `serverFingerprint`.
    /// In pairing mode the client sends `pairRequest` and the hello exchange starts after the server accepts.
    public func connect(
        host: String,
        port: UInt16,
        serverFingerprint: Data,
        mode: Mode = .normal,
        onEvent: @escaping @Sendable (SyncSession.Event) -> Void
    ) throws {
        let tls = try TLS.options(identity: identity, queue: queue) { $0 == serverFingerprint }
        let connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port)!,
            using: TLS.parameters(tls)
        )
        let session = SyncSession(connection: connection, queue: queue)
        self.session = session
        let hello = self.hello
        session.start { session, event in
            switch event {
            case .secured:
                switch mode {
                case .normal:
                    session.send(.hello(hello))
                case .pairing(let token):
                    session.send(.pairRequest(PairRequest(token: token.base64URLEncoded, deviceId: hello.deviceId, deviceName: hello.deviceName, platform: hello.platform)))
                }
            case .pairResult(let result) where result.accepted:
                session.send(.hello(hello))
            case .hello:
                // The server is pinned, so once it greets us its clips are trusted.
                session.authorizeClips()
            default:
                break
            }
            onEvent(event)
        }
    }

    public func send(_ clip: Clip) { session?.send(clip) }
    public func requestLatest() { session?.send(.requestLatest) }
    public func close() { session?.close(reason: "client closed") }
}
