import AppKit
import Network
import Observation
import OSLog
import ServiceManagement
import SynclikoProtocol
import SynclikoTransport

let log = Logger(subsystem: "com.syncliko.mac", category: "app")

/// Debug-only launch flags for automated end-to-end tests, e.g.
/// `open Syncliko.app --args -SynclikoAutoPair YES -SynclikoAutoAcceptPairing YES`.
enum DebugFlags {
    #if DEBUG
    static var autoPair: Bool { UserDefaults.standard.bool(forKey: "SynclikoAutoPair") }
    static var autoAcceptPairing: Bool { UserDefaults.standard.bool(forKey: "SynclikoAutoAcceptPairing") }
    #else
    static let autoPair = false
    static let autoAcceptPairing = false
    #endif
}

struct HistoryItem: Identifiable, Equatable {
    enum Direction: String, Codable { case sent, received }
    var id: String { clip.id }
    let clip: Clip
    let direction: Direction
    let peerName: String?
    let date: Date

    static func == (a: HistoryItem, b: HistoryItem) -> Bool { a.clip.id == b.clip.id && a.date == b.date }
}

/// A paired device as the UI shows it: online or not, and over which network.
struct DeviceState: Identifiable {
    enum Route { case wifi, tailscale }
    var id: String { device.id }
    let device: PairedDevice
    let online: Bool
    let route: Route?
}

struct PairingSession: Equatable {
    let invite: PairingInvite
    let expiresAt: Date
}

enum PairingOutcome: Equatable {
    case paired(String)
    case declined
    case failed
}

@MainActor
@Observable
final class AppModel {
    enum ServerStatus: Equatable {
        case starting
        case listening(port: UInt16)
        case failed(String)
    }

    // MARK: State shown in the UI

    private(set) var status: ServerStatus = .starting
    private(set) var connectedPeers: [ConnectedPeer] = []
    private(set) var devices: [PairedDevice] = []
    private(set) var history: [HistoryItem] = []
    private(set) var pairing: PairingSession?
    private(set) var pendingPairing: PairingCandidate?
    private(set) var pairingOutcome: PairingOutcome?
    private(set) var pasteboardAccess: NSPasteboard.AccessBehavior = .default
    /// Bumped on every sent/received clip; the menu bar icon animates on change.
    private(set) var activityPulse = 0
    /// Direction of the clip behind the last pulse, so the icon can run the right way.
    private(set) var lastActivity: HistoryItem.Direction = .sent
    private(set) var startupError: String?
    private(set) var loginItemStatus: SMAppService.Status = SMAppService.mainApp.status
    /// A copy made while no phone was connected; delivered when one connects (if still fresh).
    private(set) var waitingForPhone = false

    var isPaused = false {
        didSet {
            guard !isPreview else { return }
            UserDefaults.standard.set(isPaused, forKey: "paused")
            if !isPaused { pasteboard.resync() }
        }
    }

    var launchAtLogin: Bool {
        get { loginItemStatus == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                log.error("login item: \(error.localizedDescription, privacy: .public)")
            }
            loginItemStatus = SMAppService.mainApp.status
            if loginItemStatus == .requiresApproval { SMAppService.openSystemSettingsLoginItems() }
        }
    }

    let deviceId = LocalDevice.id
    let deviceName = LocalDevice.name
    /// New versions on GitHub Releases (see UpdateChecker).
    let updates: UpdateChecker

    /// Opens the pairing window; set by the app scene.
    var presentPairingWindow: (() -> Void)?

    // MARK: Internals

    private let deviceStore = DeviceStore()
    private let historyStore = HistoryStore()
    private var identity: TLSIdentity?
    private var server: SyncServer?
    private let pasteboard: PasteboardBridge
    private let pathMonitor = NWPathMonitor()
    private var echo = EchoGuard()
    private var lastLocalClip: Clip?
    /// When the pasteboard changed while nobody could receive it (we don't read it then).
    private var unreadChangeAt: Date?
    private var pairingTimer: Timer?
    private var observers: [NSObjectProtocol] = []
    private static let historyLimit = 30
    /// Debug snapshots (see Snapshot.swift) run on made-up data and never touch the network,
    /// the Keychain or saved settings.
    private(set) var isPreview = false
    /// Fingerprints shown as online in previews (real peers can't be constructed outside the server).
    var previewOnline: Set<String> = []

    init() {
        updates = UpdateChecker()
        pasteboard = PasteboardBridge(deviceId: LocalDevice.id)
        isPaused = UserDefaults.standard.bool(forKey: "paused")
        devices = deviceStore.load()
        history = historyStore.load()
        start()
    }

    #if DEBUG
    init(preview devices: [PairedDevice], history: [HistoryItem]) {
        updates = UpdateChecker(enabled: false)
        pasteboard = PasteboardBridge(deviceId: "mac-preview")
        isPreview = true
        self.devices = devices
        self.history = history
        status = .listening(port: SynclikoProtocol.defaultPort)
        pasteboardAccess = .alwaysAllow
        loginItemStatus = .enabled
    }

    func setPreview(devices: [PairedDevice]? = nil, history: [HistoryItem]? = nil, pairing: PairingSession?? = nil) {
        if let devices { self.devices = devices }
        if let history { self.history = history }
        if let pairing { self.pairing = pairing }
    }
    #endif

    /// Paired devices with their live state, connected ones first.
    var deviceStates: [DeviceState] {
        devices.map { device in
            let peer = connectedPeers.first { $0.fingerprint.hexString == device.fingerprintHex }
            let online = peer != nil || previewOnline.contains(device.fingerprintHex)
            let route: DeviceState.Route? = online ? (peer.map { Self.isTailscale($0.address) ? .tailscale : .wifi } ?? .wifi) : nil
            return DeviceState(device: device, online: online, route: route)
        }
        .sorted { $0.online && !$1.online }
    }

    var statusLine: String {
        if isPaused { return String(localized: "Sync is paused") }
        let online = deviceStates.filter(\.online)
        if let first = online.first {
            return online.count == 1
                ? String(localized: "Connected to \(first.device.name)")
                : String(localized: "Connected to \(online.count) devices")
        }
        switch status {
        case .starting: return String(localized: "Starting…")
        case .failed: return String(localized: "Can’t accept connections")
        case .listening:
            // Phones close the connection while their screen is off; each device row says so.
            return devices.isEmpty ? String(localized: "No devices yet") : String(localized: "No connection")
        }
    }

    var isConnected: Bool { !connectedPeers.isEmpty || !previewOnline.isEmpty }

    /// Whether a peer address ("100.101.1.2:5555", "[fd7a:115c:a1e0::1]:5555") is a Tailscale one.
    private static func isTailscale(_ address: String) -> Bool {
        let host = address.hasPrefix("[") ? String(address.dropFirst().prefix { $0 != "]" }) : String(address.split(separator: ":").first ?? "")
        if host.lowercased().hasPrefix("fd7a:115c:a1e0") { return true }
        let octets = host.split(separator: ".").compactMap { Int($0) }
        return octets.count == 4 && octets[0] == 100 && (64...127).contains(octets[1])
    }

    // MARK: Lifecycle

    private func start() {
        do {
            identity = try KeychainIdentityStore().loadOrCreate(commonName: "Syncliko \(deviceName)")
        } catch {
            startupError = String(localized: "Syncliko couldn’t read its encryption key from the Keychain. Allow access when macOS asks, then restart Syncliko.")
            status = .failed("identity")
            log.error("identity: \(String(describing: error), privacy: .public)")
            return
        }
        guard let identity else { return }
        let hello = Hello(deviceId: deviceId, deviceName: deviceName, platform: .macos, appVersion: LocalDevice.appVersion)
        let savedPort = UInt16(clamping: UserDefaults.standard.integer(forKey: "port"))
        let server = SyncServer(configuration: .init(
            identity: identity,
            hello: hello,
            preferredPort: savedPort == 0 ? SynclikoProtocol.defaultPort : savedPort,
            bonjourName: deviceName
        )) { [weak self] event in
            // Hop to the main thread preserving event order.
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.handle(event) } }
        }
        self.server = server
        NetworkInterfaces.refreshTailscaleName()
        server.start(trustedFingerprints: trustedFingerprints) { port in NetworkInterfaces.endpoints(port: port) }

        pasteboard.onChange = { [weak self] in self?.pasteboardChanged() }
        pasteboard.start()
        refreshPasteboardAccess()

        pathMonitor.pathUpdateHandler = { [weak server] _ in
            NetworkInterfaces.refreshTailscaleName()
            server?.restartIfNeeded()
            server?.refreshEndpoints()
        }
        pathMonitor.start(queue: .global(qos: .utility))
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.server?.restartIfNeeded()
                self?.server?.refreshEndpoints()
            }
        })
        observers.append(NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.server?.stop() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.refreshPasteboardAccess()
                self?.loginItemStatus = SMAppService.mainApp.status
            }
        })
    }

    private var trustedFingerprints: Set<Data> {
        Set(devices.compactMap { Data(hexString: $0.fingerprintHex) })
    }

    func refreshPasteboardAccess() {
        pasteboardAccess = pasteboard.accessBehavior
        loginItemStatus = SMAppService.mainApp.status
    }

    /// First launch with nothing paired: show the pairing window right away (menu bar apps have no window).
    func showWelcomeIfNeeded() {
        guard !isPreview, devices.isEmpty, !UserDefaults.standard.bool(forKey: "welcomed") else { return }
        UserDefaults.standard.set(true, forKey: "welcomed")
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(600))
            self.beginPairing()
            self.presentPairingWindow?()
        }
    }

    // MARK: Clipboard flow

    /// The pasteboard changed. We read it only when someone can receive it — fewer reads means fewer
    /// privacy prompts and no needless exposure of what the user copies.
    private func pasteboardChanged() {
        refreshPasteboardAccess()
        guard !isPaused, pasteboardAccess != .alwaysDeny else { return }
        guard isConnected else {
            unreadChangeAt = Date()
            waitingForPhone = !devices.isEmpty
            return
        }
        readAndSend()
    }

    private func readAndSend(onlyTo peer: ConnectedPeer? = nil) {
        pasteboard.read { [weak self] clip in
            guard let self, let clip else { return }
            if echo.shouldSuppress(clip.header.sha256) { return }
            echo.note(clip.header.sha256)
            lastLocalClip = clip
            unreadChangeAt = nil
            waitingForPhone = false
            if let peer {
                server?.send(clip, to: peer.fingerprint)
            } else {
                server?.broadcast(clip)
            }
            log.notice("sent \(clip.kind.rawValue, privacy: .public) \(clip.content.count) B")
            record(clip, .sent, peer: connectedPeers.count == 1 ? connectedPeers[0].hello.deviceName : nil)
        }
    }

    private func received(_ clip: Clip, from peer: ConnectedPeer) {
        log.notice("received \(clip.kind.rawValue, privacy: .public) \(clip.content.count) B from \(peer.hello.deviceName, privacy: .public)")
        guard !isPaused else { return }
        echo.note(clip.header.sha256)
        lastLocalClip = clip
        pasteboard.write(clip)
        // Forward to other connected devices (phone → Mac → tablet).
        for other in connectedPeers where other.fingerprint != peer.fingerprint {
            server?.send(clip, to: other.fingerprint)
        }
        record(clip, .received, peer: peer.hello.deviceName)
    }

    private func record(_ clip: Clip, _ direction: HistoryItem.Direction, peer: String?) {
        lastActivity = direction
        activityPulse += 1
        history.removeAll { $0.clip.header.sha256 == clip.header.sha256 }
        history.insert(HistoryItem(clip: clip, direction: direction, peerName: peer, date: Date()), at: 0)
        if history.count > Self.historyLimit { history.removeLast(history.count - Self.historyLimit) }
        historyStore.save(history)
    }

    func restore(_ item: HistoryItem) {
        echo.reset()
        pasteboard.restore(item.clip)
    }

    func delete(_ item: HistoryItem) {
        history.removeAll { $0.id == item.id }
        historyStore.save(history)
    }

    func clearHistory() {
        history.removeAll()
        historyStore.save(history)
    }

    // MARK: Server events

    private func handle(_ event: SyncServer.Event) {
        switch event {
        case .listening(let port):
            status = .listening(port: port)
            UserDefaults.standard.set(Int(port), forKey: "port")
            log.notice("listening on port \(port, privacy: .public)")
            if DebugFlags.autoPair, pairing == nil { beginPairing() }
        case .listenerFailed(let message):
            status = .failed(message)
            log.error("listener: \(message, privacy: .public)")
        case .peerConnected(let peer):
            log.notice("connected: \(peer.hello.deviceName, privacy: .public) @ \(peer.address, privacy: .public)")
            connectedPeers.removeAll { $0.fingerprint == peer.fingerprint }
            connectedPeers.append(peer)
            updateDeviceName(peer)
            // Deliver a copy made while the phone was away, like Universal Clipboard does.
            if let at = unreadChangeAt, Date().timeIntervalSince(at) < SynclikoProtocol.latestMaxAge, !isPaused {
                readAndSend(onlyTo: peer)
            }
        case .peerDisconnected(let fingerprint):
            connectedPeers.removeAll { $0.fingerprint == fingerprint }
        case .clipReceived(let clip, let peer):
            received(clip, from: peer)
        case .requestLatest(let peer):
            if !isPaused, let clip = lastLocalClip,
               Date().timeIntervalSince(clip.createdAt) < SynclikoProtocol.latestMaxAge {
                server?.send(clip, to: peer.fingerprint)
            }
        case .pairingRequested(let candidate):
            pendingPairing = candidate
            log.notice("pairing requested by \(candidate.request.deviceName, privacy: .public)")
            if DebugFlags.autoAcceptPairing {
                resolvePairing(accept: true)
            } else {
                presentPairingWindow?()
                NSApp.activate()
            }
        case .pairingFinished(let device, let candidateID, _):
            if let candidateID, pendingPairing?.id == candidateID { pendingPairing = nil }
            if let device {
                devices.removeAll { $0.fingerprintHex == device.fingerprintHex || $0.deviceId == device.deviceId }
                devices.append(device)
                deviceStore.save(devices)
                server?.setTrusted(trustedFingerprints)
                pairingOutcome = .paired(device.name)
                closePairingWindowState()
            } else if pendingPairing == nil, pairingOutcome == nil, pairing != nil {
                pairingOutcome = .failed
            }
        }
    }

    private func updateDeviceName(_ peer: ConnectedPeer) {
        guard let index = devices.firstIndex(where: { $0.fingerprintHex == peer.fingerprint.hexString }),
              devices[index].name != peer.hello.deviceName else { return }
        devices[index].name = peer.hello.deviceName
        deviceStore.save(devices)
    }

    // MARK: Pairing

    func beginPairing() {
        guard let server, let identity, case .listening(let port) = status else { return }
        pairingOutcome = nil
        let duration: TimeInterval = 300
        let token = server.openPairingWindow(duration: duration)
        let hosts = NetworkInterfaces.endpoints(port: port).map(\.host)
        let invite = PairingInvite(deviceId: deviceId, deviceName: deviceName, fingerprint: identity.fingerprint, token: token, port: Int(port), hosts: hosts)
        pairing = PairingSession(invite: invite, expiresAt: Date().addingTimeInterval(duration))
        #if DEBUG
        log.notice("pairing invite \(invite.url.absoluteString, privacy: .public)")
        #endif
        pairingTimer?.invalidate()
        pairingTimer = Timer.scheduledTimer(withTimeInterval: duration, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.endPairing() }
        }
    }

    func endPairing() {
        server?.closePairingWindow()
        closePairingWindowState()
    }

    private func closePairingWindowState() {
        pairing = nil
        pairingTimer?.invalidate()
        pairingTimer = nil
    }

    func resolvePairing(accept: Bool) {
        guard let candidate = pendingPairing else { return }
        server?.resolvePairing(candidate.id, accept: accept)
        pendingPairing = nil
        if !accept {
            pairingOutcome = .declined
            closePairingWindowState()
        }
    }

    func remove(_ device: PairedDevice) {
        devices.removeAll { $0.id == device.id }
        deviceStore.save(devices)
        server?.setTrusted(trustedFingerprints)
        connectedPeers.removeAll { $0.fingerprint.hexString == device.fingerprintHex }
    }

    var macFingerprintShort: String {
        guard let fp = identity?.fingerprint.hexString else { return "—" }
        return stride(from: 0, to: 16, by: 4).map { i in
            String(fp[fp.index(fp.startIndex, offsetBy: i)..<fp.index(fp.startIndex, offsetBy: i + 4)])
        }.joined(separator: " ")
    }

    func openPasteboardPrivacySettings() {
        let urls = [
            "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_Pasteboard",
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Pasteboard",
            "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension",
        ]
        for string in urls {
            if let url = URL(string: string), NSWorkspace.shared.open(url) { return }
        }
    }
}

/// Suppresses echoes (content we just sent or received coming back through another path, e.g. the
/// Android emulator's host-clipboard bridge) without blocking a deliberate re-copy later on.
struct EchoGuard {
    private var hash: String?
    private var at = Date.distantPast
    private let window: TimeInterval = 15

    func shouldSuppress(_ h: String) -> Bool { h == hash && Date().timeIntervalSince(at) < window }
    mutating func note(_ h: String) { hash = h; at = Date() }
    mutating func reset() { hash = nil }
}
