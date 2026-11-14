import Foundation
import Network
import Synchronization
import SynclikoProtocol

/// A device that completed pairing.
public struct PairedDevice: Codable, Sendable, Equatable, Identifiable, Hashable {
    public var id: String { fingerprintHex }
    public var fingerprintHex: String
    public var deviceId: String
    public var name: String
    public var platform: Platform
    public var pairedAt: Date

    public init(fingerprint: Data, deviceId: String, name: String, platform: Platform, pairedAt: Date = Date()) {
        self.fingerprintHex = fingerprint.hexString
        self.deviceId = deviceId
        self.name = name
        self.platform = platform
        self.pairedAt = pairedAt
    }
}

/// A pairing request waiting for the user's confirmation.
public struct PairingCandidate: Sendable, Equatable, Identifiable {
    public let id: UUID
    public let request: PairRequest
    public let fingerprint: Data
    /// Six-digit code the phone shows too ([PairingCode]); the user compares them before accepting.
    public let code: String
}

public struct ConnectedPeer: Sendable, Equatable, Identifiable {
    public var id: String { fingerprint.hexString }
    public let fingerprint: Data
    public let hello: Hello
    public let address: String
}

/// TLS listener the Android clients connect to (the Mac side of PROTOCOL.md).
public final class SyncServer: @unchecked Sendable {
    public struct Configuration: Sendable {
        public var identity: TLSIdentity
        public var hello: Hello
        public var preferredPort: UInt16
        public var bonjourName: String?

        public init(identity: TLSIdentity, hello: Hello, preferredPort: UInt16 = SynclikoProtocol.defaultPort, bonjourName: String? = nil) {
            self.identity = identity
            self.hello = hello
            self.preferredPort = preferredPort
            self.bonjourName = bonjourName
        }
    }

    public enum Event: Sendable {
        case listening(port: UInt16)
        case listenerFailed(String)
        case peerConnected(ConnectedPeer)
        case peerDisconnected(fingerprint: Data)
        case clipReceived(Clip, from: ConnectedPeer)
        case requestLatest(from: ConnectedPeer)
        case pairingRequested(PairingCandidate)
        case pairingFinished(PairedDevice?, candidate: UUID?, reason: String?)
    }

    private struct PairingWindow {
        var token: Data
        var expiresAt: Date
        var isOpen: Bool { Date() < expiresAt }
    }

    private enum SessionRole {
        case provisional
        case awaitingConfirmation(PairingCandidate)
        case trusted
        case authenticated(ConnectedPeer)
    }

    public let configuration: Configuration
    private let queue = DispatchQueue(label: "syncliko.server")
    private var listener: NWListener?
    private var sessions: [UUID: (session: SyncSession, role: SessionRole)] = [:]
    private var trusted: Set<Data> = []
    /// Guarded by a mutex (not `queue`) so it can be opened from any thread, including event handlers.
    private let pairingState = Mutex<PairingWindow?>(nil)
    private var pairing: PairingWindow? {
        get { pairingState.withLock { $0 } }
        set { pairingState.withLock { $0 = newValue } }
    }
    private var endpointsProvider: @Sendable (UInt16) -> [Endpoint] = { _ in [] }
    private let onEvent: @Sendable (Event) -> Void
    public private(set) var port: UInt16?

    public init(configuration: Configuration, onEvent: @escaping @Sendable (Event) -> Void) {
        self.configuration = configuration
        self.onEvent = onEvent
    }

    // MARK: - Lifecycle

    public func start(trustedFingerprints: Set<Data>, endpoints: @escaping @Sendable (UInt16) -> [Endpoint] = { _ in [] }) {
        queue.async { [self] in
            trusted = trustedFingerprints
            endpointsProvider = endpoints
            startListener(port: configuration.preferredPort)
        }
    }

    public func stop() {
        queue.async { [self] in
            listener?.cancel()
            listener = nil
            for entry in sessions.values { entry.session.close(reason: "server stopped") }
            sessions.removeAll()
        }
    }

    public func setTrusted(_ fingerprints: Set<Data>) {
        queue.async { [self] in
            trusted = fingerprints
            // Drop sessions of devices that were removed.
            for (id, entry) in sessions {
                let fp: Data? = switch entry.role {
                case .authenticated(let peer): peer.fingerprint
                case .trusted: entry.session.peerFingerprint
                default: nil
                }
                if let fp, !fingerprints.contains(fp) {
                    entry.session.close(after: .error(ProtocolErrorMessage(code: .unauthorized, message: "device removed")), reason: "device removed")
                    sessions[id] = nil
                }
            }
        }
    }

    // MARK: - Sync

    public func broadcast(_ clip: Clip) {
        queue.async { [self] in
            for entry in sessions.values {
                if case .authenticated = entry.role { entry.session.send(clip) }
            }
        }
    }

    public func send(_ clip: Clip, to fingerprint: Data) {
        queue.async { [self] in
            for entry in sessions.values {
                if case .authenticated(let peer) = entry.role, peer.fingerprint == fingerprint { entry.session.send(clip) }
            }
        }
    }

    public func refreshEndpoints() {
        queue.async { [self] in
            guard let port else { return }
            let list = endpointsProvider(port)
            for entry in sessions.values {
                if case .authenticated = entry.role { entry.session.send(.endpoints(Endpoints(endpoints: list))) }
            }
        }
    }

    // MARK: - Pairing

    /// Opens a pairing window and returns the one-time token to embed in the QR code.
    public func openPairingWindow(duration: TimeInterval = 300) -> Data {
        var token = Data(count: 16)
        let status = token.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        if status != errSecSuccess {
            var rng = SystemRandomNumberGenerator()
            token = Data((0..<16).map { _ in UInt8.random(in: .min ... .max, using: &rng) })
        }
        pairing = PairingWindow(token: token, expiresAt: Date().addingTimeInterval(duration))
        return token
    }

    public func closePairingWindow() {
        pairing = nil
    }

    /// Completes a pending pairing after the user confirmed or rejected it on the Mac.
    public func resolvePairing(_ candidateID: UUID, accept: Bool) {
        queue.async { [self] in
            guard let (sid, entry) = sessions.first(where: {
                if case .awaitingConfirmation(let c) = $0.value.role { return c.id == candidateID }
                return false
            }), case .awaitingConfirmation(let candidate) = entry.role else { return }

            let me = configuration.hello
            if accept {
                trusted.insert(candidate.fingerprint)
                pairing = nil
                sessions[sid]?.role = .trusted
                entry.session.send(.pairResult(PairResult(accepted: true, deviceId: me.deviceId, deviceName: me.deviceName)))
                entry.session.send(.hello(me))
                entry.session.authorizeClips()
                let device = PairedDevice(fingerprint: candidate.fingerprint, deviceId: candidate.request.deviceId, name: candidate.request.deviceName, platform: candidate.request.platform)
                onEvent(.pairingFinished(device, candidate: candidate.id, reason: nil))
            } else {
                entry.session.close(after: .pairResult(PairResult(accepted: false, deviceId: me.deviceId, deviceName: me.deviceName, reason: "rejected")), reason: "pairing rejected")
                sessions[sid] = nil
                onEvent(.pairingFinished(nil, candidate: candidate.id, reason: "rejected"))
            }
        }
    }

    // MARK: - Private

    private func startListener(port: UInt16) {
        let tls: NWProtocolTLS.Options
        do {
            tls = try TLS.options(identity: configuration.identity, queue: queue) { [weak self] fingerprint in
                guard let self else { return false }
                // Called on `queue`. Unknown certificates are allowed only while pairing.
                return trusted.contains(fingerprint) || (pairing?.isOpen ?? false)
            }
        } catch {
            onEvent(.listenerFailed("\(error)"))
            return
        }
        let params = TLS.parameters(tls)
        params.allowLocalEndpointReuse = false
        let listener: NWListener
        do {
            listener = port == 0 ? try NWListener(using: params) : try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
        } catch {
            if port != 0 { startListener(port: 0) } else { onEvent(.listenerFailed("\(error)")) }
            return
        }
        if let name = configuration.bonjourName {
            let txt = NWTXTRecord([
                "v": String(SynclikoProtocol.version),
                "id": configuration.hello.deviceId,
                "fp": String(configuration.identity.fingerprint.base64URLEncoded.prefix(16)),
            ])
            listener.service = NWListener.Service(name: name, type: SynclikoProtocol.bonjourType, txtRecord: txt)
        }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.listenerRetry = 0
                self.port = listener?.port?.rawValue
                if let p = self.port { onEvent(.listening(port: p)) }
            case .failed(let error):
                listener?.cancel()
                self.listener = nil
                if port != 0, case .posix(.EADDRINUSE) = error {
                    startListener(port: 0)
                } else {
                    onEvent(.listenerFailed("\(error)"))
                    scheduleListenerRestart()
                }
            case .waiting(let error):
                onEvent(.listenerFailed("waiting: \(error)"))
            default: break
            }
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    private var listenerRetry = 0

    /// Restart a failed listener (mDNSResponder restarts, sleep/wake, network changes) with backoff.
    private func scheduleListenerRestart() {
        listenerRetry += 1
        let delay = min(30.0, pow(2.0, Double(listenerRetry - 1)))
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, listener == nil else { return }
            startListener(port: configuration.preferredPort)
        }
    }

    /// Call after wake or a network change: brings the listener back if it died.
    public func restartIfNeeded() {
        queue.async { [self] in
            if listener == nil { startListener(port: configuration.preferredPort) }
        }
    }

    private static let maxSessions = 16
    private static let maxUnauthenticated = 4

    private func accept(_ connection: NWConnection) {
        let unauthenticated = sessions.values.filter { if case .authenticated = $0.role { false } else { true } }.count
        guard sessions.count < Self.maxSessions, unauthenticated < Self.maxUnauthenticated else {
            connection.cancel()
            return
        }
        let session = SyncSession(connection: connection, queue: queue)
        sessions[session.id] = (session, .provisional)
        session.start { [weak self] session, event in self?.handle(event, from: session) }
    }

    /// Closes the session if it is still in `role`-like state after `seconds` (slow-loris guard).
    private func deadline(_ session: SyncSession, seconds: TimeInterval, unless done: @escaping (SessionRole) -> Bool) {
        queue.asyncAfter(deadline: .now() + seconds) { [weak self, weak session] in
            guard let self, let session, let role = sessions[session.id]?.role, !done(role) else { return }
            session.close(reason: "timed out waiting for the peer")
        }
    }

    private func handle(_ event: SyncSession.Event, from session: SyncSession) {
        guard let role = sessions[session.id]?.role else { return }
        let me = configuration.hello
        switch (event, role) {
        case (.secured(let fp), .provisional):
            if trusted.contains(fp) {
                sessions[session.id]?.role = .trusted
                session.send(.hello(me))
                deadline(session, seconds: 15) { if case .authenticated = $0 { true } else { false } }
            } else {
                // Unknown certificate (only possible while pairing): it must ask to pair promptly.
                deadline(session, seconds: 15) { if case .provisional = $0 { false } else { true } }
            }

        case (.pairRequest(let request), .provisional):
            guard let window = pairing, window.isOpen else {
                session.close(after: .error(ProtocolErrorMessage(code: .pairingClosed, message: "pairing window closed")), reason: "pairing closed")
                return
            }
            guard let token = Data(base64URLEncoded: request.token), token.constantTimeEquals(window.token),
                  let fp = session.peerFingerprint else {
                session.close(after: .error(ProtocolErrorMessage(code: .badToken, message: "invalid pairing token")), reason: "bad token")
                return
            }
            // The token is single-use and only one request can wait for the user at a time.
            pairing = nil
            let candidate = PairingCandidate(
                id: UUID(),
                request: request,
                fingerprint: fp,
                code: PairingCode.make(macFingerprint: configuration.identity.fingerprint, phoneFingerprint: fp)
            )
            sessions[session.id]?.role = .awaitingConfirmation(candidate)
            deadline(session, seconds: 120) { if case .awaitingConfirmation = $0 { false } else { true } }
            onEvent(.pairingRequested(candidate))

        case (.hello(let hello), .trusted):
            guard let fp = session.peerFingerprint, trusted.contains(fp) else {
                session.close(after: .error(ProtocolErrorMessage(code: .unauthorized, message: "device removed")), reason: "untrusted hello")
                return
            }
            session.authorizeClips()
            let peer = ConnectedPeer(fingerprint: fp, hello: hello, address: "\(session.remoteEndpoint)")
            // A device reconnecting replaces its older session.
            for (id, entry) in sessions where id != session.id {
                if case .authenticated(let old) = entry.role, old.fingerprint == fp {
                    entry.session.close(reason: "replaced")
                    sessions[id] = nil
                }
            }
            sessions[session.id]?.role = .authenticated(peer)
            if let port { session.send(.endpoints(Endpoints(endpoints: endpointsProvider(port)))) }
            onEvent(.peerConnected(peer))

        case (.clip(let clip), .authenticated(let peer)):
            onEvent(.clipReceived(clip, from: peer))

        case (.requestLatest, .authenticated(let peer)):
            onEvent(.requestLatest(from: peer))

        case (.closed, _):
            sessions[session.id] = nil
            switch role {
            case .authenticated(let peer): onEvent(.peerDisconnected(fingerprint: peer.fingerprint))
            case .awaitingConfirmation(let candidate): onEvent(.pairingFinished(nil, candidate: candidate.id, reason: "connection closed"))
            default: break
            }

        case (.ack, _), (.remoteError, _), (.endpoints, _), (.pairResult, _), (.secured, _), (.hello, .authenticated):
            break

        default:
            // Anything else before authentication is a protocol violation.
            session.close(after: .error(ProtocolErrorMessage(code: .unauthorized, message: "not authenticated")), reason: "unauthorized frame")
        }
    }
}
