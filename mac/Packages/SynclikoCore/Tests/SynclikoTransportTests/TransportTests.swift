import Foundation
import Synchronization
import Testing
import SynclikoProtocol
@testable import SynclikoTransport

/// Thread-safe event log with async waiting.
final class Recorder<E: Sendable>: Sendable {
    private let events = Mutex<[E]>([])

    func record(_ event: E) { events.withLock { $0.append(event) } }

    var all: [E] { events.withLock { $0 } }

    func wait<T>(timeout: TimeInterval = 5, _ match: (E) -> T?) async throws -> T {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let hit = all.lazy.compactMap(match).first { return hit }
            try await Task.sleep(for: .milliseconds(20))
        }
        throw TimeoutError()
    }

    struct TimeoutError: Error {}
}

/// Mac ↔ client over real TLS on localhost.
@Suite(.serialized) struct TransportTests {
    let serverIdentity = try! TLSIdentity.generate(commonName: "Test Mac")
    let clientIdentity = try! TLSIdentity.generate(commonName: "Test Phone")
    let macHello = Hello(deviceId: "mac-1", deviceName: "Test Mac", platform: .macos, appVersion: "test")
    let phoneHello = Hello(deviceId: "android-1", deviceName: "Test Phone", platform: .android, appVersion: "test")

    func startServer(trusted: Set<Data> = []) async throws -> (SyncServer, Recorder<SyncServer.Event>, UInt16) {
        let recorder = Recorder<SyncServer.Event>()
        let server = SyncServer(configuration: .init(identity: serverIdentity, hello: macHello, preferredPort: 0)) { recorder.record($0) }
        server.start(trustedFingerprints: trusted) { port in [Endpoint(host: "127.0.0.1", port: Int(port), kind: .lan)] }
        let port = try await recorder.wait { if case .listening(let p) = $0 { p } else { nil } }
        return (server, recorder, port)
    }

    @Test func identityProducesValidTLSIdentity() throws {
        _ = try serverIdentity.secIdentity()
        #expect(serverIdentity.fingerprint.count == 32)
        #expect(serverIdentity.fingerprint != clientIdentity.fingerprint)
    }

    @Test func pairThenSyncBothWays() async throws {
        let (server, serverEvents, port) = try await startServer()
        defer { server.stop() }
        let token = server.openPairingWindow()

        let clientEvents = Recorder<SyncSession.Event>()
        let client = SyncClient(identity: clientIdentity, hello: phoneHello)
        try client.connect(host: "127.0.0.1", port: port, serverFingerprint: serverIdentity.fingerprint, mode: .pairing(token: token)) {
            clientEvents.record($0)
        }

        // Server asks the user; we accept.
        let candidate = try await serverEvents.wait { if case .pairingRequested(let c) = $0 { c } else { nil } }
        #expect(candidate.fingerprint == clientIdentity.fingerprint)
        #expect(candidate.request.deviceName == "Test Phone")
        #expect(candidate.code == PairingCode.make(macFingerprint: serverIdentity.fingerprint, phoneFingerprint: clientIdentity.fingerprint))
        server.resolvePairing(candidate.id, accept: true)

        let device = try await serverEvents.wait { if case .pairingFinished(let d?, _, _) = $0 { d } else { nil } }
        #expect(device.deviceId == "android-1")
        _ = try await clientEvents.wait { if case .pairResult(let r) = $0, r.accepted { r } else { nil } }
        let macHelloSeen = try await clientEvents.wait { if case .hello(let h) = $0 { h } else { nil } }
        #expect(macHelloSeen.deviceId == "mac-1")
        let peer = try await serverEvents.wait { if case .peerConnected(let p) = $0 { p } else { nil } }
        #expect(peer.hello == phoneHello)
        let endpoints = try await clientEvents.wait { if case .endpoints(let e) = $0 { e } else { nil } }
        #expect(endpoints.first?.port == Int(port))

        // Phone → Mac (text).
        let text = Clip.text("Привет с телефона", origin: "android-1")
        client.send(text)
        let received = try await serverEvents.wait { if case .clipReceived(let c, _) = $0 { c } else { nil } }
        #expect(received == text)
        _ = try await clientEvents.wait { if case .ack(let id) = $0, id == text.id { id } else { nil } }

        // Mac → phone (chunked image, ~700 KB).
        let image = Clip.make(kind: .image, mime: "image/png", content: Data((0..<700_000).map { UInt8($0 % 253) }), origin: "mac-1")
        server.broadcast(image)
        let gotImage = try await clientEvents.wait { if case .clip(let c) = $0, c.kind == .image { c } else { nil } }
        #expect(gotImage == image)
        client.close()
    }

    @Test func pairedDeviceReconnectsWithoutPairing() async throws {
        let (server, serverEvents, port) = try await startServer(trusted: [clientIdentity.fingerprint])
        defer { server.stop() }
        let clientEvents = Recorder<SyncSession.Event>()
        let client = SyncClient(identity: clientIdentity, hello: phoneHello)
        try client.connect(host: "127.0.0.1", port: port, serverFingerprint: serverIdentity.fingerprint) { clientEvents.record($0) }
        _ = try await serverEvents.wait { if case .peerConnected(let p) = $0 { p } else { nil } }
        client.requestLatest()
        _ = try await serverEvents.wait { if case .requestLatest = $0 { true } else { nil } }
        client.close()
        _ = try await serverEvents.wait { if case .peerDisconnected = $0 { true } else { nil } }
    }

    @Test func unknownDeviceIsRejectedOutsidePairingWindow() async throws {
        let (server, serverEvents, port) = try await startServer()
        defer { server.stop() }
        let clientEvents = Recorder<SyncSession.Event>()
        let client = SyncClient(identity: clientIdentity, hello: phoneHello)
        try client.connect(host: "127.0.0.1", port: port, serverFingerprint: serverIdentity.fingerprint) { clientEvents.record($0) }
        _ = try await clientEvents.wait { if case .closed = $0 { true } else { nil } }
        #expect(!serverEvents.all.contains { if case .peerConnected = $0 { true } else { false } })
    }

    @Test func clientRejectsWrongServerFingerprint() async throws {
        let (server, serverEvents, port) = try await startServer(trusted: [clientIdentity.fingerprint])
        defer { server.stop() }
        let clientEvents = Recorder<SyncSession.Event>()
        let client = SyncClient(identity: clientIdentity, hello: phoneHello)
        try client.connect(host: "127.0.0.1", port: port, serverFingerprint: Data(count: 32)) { clientEvents.record($0) }
        _ = try await clientEvents.wait { if case .closed = $0 { true } else { nil } }
        #expect(!clientEvents.all.contains { if case .secured = $0 { true } else { false } })
        #expect(!serverEvents.all.contains { if case .peerConnected = $0 { true } else { false } })
    }

    @Test func badPairingTokenIsRejected() async throws {
        let (server, serverEvents, port) = try await startServer()
        defer { server.stop() }
        _ = server.openPairingWindow()
        let clientEvents = Recorder<SyncSession.Event>()
        let client = SyncClient(identity: clientIdentity, hello: phoneHello)
        try client.connect(host: "127.0.0.1", port: port, serverFingerprint: serverIdentity.fingerprint, mode: .pairing(token: Data(count: 16))) {
            clientEvents.record($0)
        }
        let error = try await clientEvents.wait { if case .remoteError(let e) = $0 { e } else { nil } }
        #expect(error.code == .badToken)
        #expect(!serverEvents.all.contains { if case .pairingRequested = $0 { true } else { false } })
    }

    @Test func listsInterfaces() {
        for endpoint in NetworkInterfaces.endpoints(port: 1) { #expect(!endpoint.host.isEmpty) }
    }
}
