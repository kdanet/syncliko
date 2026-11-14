import Foundation
import Network
import SynclikoProtocol

/// One TLS connection to a peer: framing, hello, clip reassembly, keepalive.
/// All state is confined to `queue`; events are delivered on `queue`.
public final class SyncSession: @unchecked Sendable {
    public enum Event: Sendable {
        /// TLS is up. `peerFingerprint` is the pinned identity of the other side.
        case secured(peerFingerprint: Data)
        case hello(Hello)
        case pairRequest(PairRequest)
        case pairResult(PairResult)
        case clip(Clip)
        case ack(String)
        case requestLatest
        case endpoints([Endpoint])
        case remoteError(ProtocolErrorMessage)
        case closed(String?)
    }

    public let id = UUID()
    public let queue: DispatchQueue
    private let connection: NWConnection
    private var decoder = FrameDecoder()
    private var assembler = ClipAssembler()
    private var pingTimer: DispatchSourceTimer?
    private var lastReceived = Date()
    private var closed = false
    private var ready = false
    /// Clip frames are dropped until the owner authorizes this session (after hello / pinned server).
    /// Prevents an unauthenticated peer on a provisional (pairing) connection from making us buffer data.
    private var clipsAllowed = false
    private var handler: (@Sendable (SyncSession, Event) -> Void)?

    public private(set) var peerFingerprint: Data?
    public private(set) var peerHello: Hello?
    public var remoteEndpoint: NWEndpoint { connection.endpoint }

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
    }

    func start(handler: @escaping @Sendable (SyncSession, Event) -> Void) {
        self.handler = handler
        connection.stateUpdateHandler = { [weak self] state in self?.handle(state) }
        connection.start(queue: queue)
        // A peer that never completes TLS must not hold a slot forever.
        queue.asyncAfter(deadline: .now() + 10) { [weak self] in
            guard let self, !ready else { return }
            close(reason: "handshake timeout")
        }
    }

    /// Allow clip frames on this session. Must be called on `queue` (i.e. from an event handler).
    public func authorizeClips() {
        dispatchPrecondition(condition: .onQueue(queue))
        clipsAllowed = true
    }

    public func send(_ message: Message) {
        queue.async { [self] in
            guard !closed else { return }
            connection.send(content: SynclikoProtocol.encode(message).encoded(), completion: .contentProcessed { [weak self] error in
                if let error { self?.close(reason: "send failed: \(error)") }
            })
        }
    }

    public func send(_ clip: Clip) {
        for message in clip.messages() { send(message) }
    }

    /// Sends a final message, then closes once it has been flushed (or after 1 s).
    public func close(after message: Message, reason: String? = nil) {
        queue.async { [self] in
            guard !closed else { return }
            connection.send(content: SynclikoProtocol.encode(message).encoded(), completion: .contentProcessed { [weak self] _ in
                self?.close(reason: reason)
            })
            queue.asyncAfter(deadline: .now() + 1) { [weak self] in self?.close(reason: reason) }
        }
    }

    public func close(reason: String? = nil) {
        queue.async { [self] in
            guard !closed else { return }
            closed = true
            pingTimer?.cancel()
            connection.cancel()
            handler?(self, .closed(reason))
            handler = nil
        }
    }

    // MARK: - Private

    private func handle(_ state: NWConnection.State) {
        switch state {
        case .ready:
            ready = true
            guard let fingerprint = TLS.peerFingerprint(of: connection) else {
                close(reason: "no peer certificate")
                return
            }
            peerFingerprint = fingerprint
            lastReceived = Date()
            startKeepalive()
            emit(.secured(peerFingerprint: fingerprint))
            receive()
        case .failed(let error):
            close(reason: "\(error)")
        case .waiting(let error):
            close(reason: "\(error)")
        case .cancelled:
            close(reason: nil)
        default:
            break
        }
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                lastReceived = Date()
                do {
                    for frame in try decoder.append(data) { process(frame) }
                } catch {
                    close(after: .error(ProtocolErrorMessage(code: .tooLarge, message: "\(error)")), reason: "framing: \(error)")
                    return
                }
            }
            if isComplete { close(reason: "peer closed") } else if let error { close(reason: "\(error)") } else if !closed { receive() }
        }
    }

    private func process(_ frame: Frame) {
        let message: Message
        do {
            message = try SynclikoProtocol.decode(frame)
        } catch MessageDecodingError.unknownType(let t) {
            send(.error(ProtocolErrorMessage(code: .unknownType, message: "type 0x\(String(t, radix: 16))")))
            return
        } catch {
            send(.error(ProtocolErrorMessage(code: .badFrame, message: "\(error)")))
            return
        }
        switch message {
        case .hello(let hello):
            guard hello.protocolVersion == SynclikoProtocol.version else {
                close(after: .error(ProtocolErrorMessage(code: .version, message: "expected \(SynclikoProtocol.version)")), reason: "version mismatch")
                return
            }
            peerHello = hello
            emit(.hello(hello))
        case .pairRequest(let r): emit(.pairRequest(r))
        case .pairResult(let r): emit(.pairResult(r))
        case .clip(let header, let data):
            guard clipsAllowed else { return }
            reassemble { try $0.begin(header, firstChunk: data) }
        case .clipChunk(let chunk, let data):
            guard clipsAllowed else { return }
            reassemble { try $0.append(chunk, data: data) }
        case .ack(let a): emit(.ack(a.id))
        case .ping(let p): send(.pong(p))
        case .pong: break
        case .requestLatest: emit(.requestLatest)
        case .endpoints(let e): emit(.endpoints(e.endpoints))
        case .error(let e): emit(.remoteError(e))
        }
    }

    private func reassemble(_ step: (inout ClipAssembler) throws -> Clip?) {
        do {
            if let clip = try step(&assembler) {
                send(.ack(Ack(id: clip.id)))
                emit(.clip(clip))
            }
        } catch ClipAssemblyError.hashMismatch(let id) {
            send(.error(ProtocolErrorMessage(code: .hashMismatch, message: id)))
        } catch ClipAssemblyError.tooLarge(let size) {
            send(.error(ProtocolErrorMessage(code: .tooLarge, message: "\(size)")))
        } catch {
            send(.error(ProtocolErrorMessage(code: .badFrame, message: "\(error)")))
        }
    }

    private func startKeepalive() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 15, repeating: 15, leeway: .seconds(2))
        var lastPing = Date()
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            let now = Date()
            if now.timeIntervalSince(lastReceived) > SynclikoProtocol.idleTimeout {
                close(reason: "idle timeout")
            } else if now.timeIntervalSince(lastPing) >= SynclikoProtocol.pingInterval {
                lastPing = now
                send(.ping(Ping(t: Int64(now.timeIntervalSince1970 * 1000))))
            }
        }
        timer.resume()
        pingTimer = timer
    }

    private func emit(_ event: Event) { handler?(self, event) }
}
