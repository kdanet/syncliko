import AppKit
import SwiftUI

@main
struct SynclikoApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model: AppModel
    /// Settings → General → "Show in menu bar". Hidden, Syncliko keeps syncing; opening the app again
    /// (Finder, Spotlight, Launchpad) brings up Settings instead (see AppDelegate).
    @AppStorage(AppDelegate.showInMenuBarKey) private var showInMenuBar = true

    init() {
        #if DEBUG
        if Snapshot.directory != nil {
            let preview = Snapshot.makeModel()
            _model = State(initialValue: preview)
            DispatchQueue.main.async { Snapshot.run(preview) }
            return
        }
        if UserDefaults.standard.bool(forKey: "SynclikoSettingsPreview") {
            // Debug: the Settings window on made-up data, without the server or the Keychain.
            let preview = Snapshot.makeModel()
            _model = State(initialValue: preview)
            WindowPresenter.shared.attach(preview)
            DispatchQueue.main.async {
                WindowPresenter.shared.showSettings()
                WindowPresenter.shared.selectSettingsTab(UserDefaults.standard.integer(forKey: "SynclikoSettingsTab"))
            }
            return
        }
        #endif
        let model = AppModel()
        _model = State(initialValue: model)
        // Set up here rather than from the menu bar item, which may be hidden.
        WindowPresenter.shared.attach(model)
        DispatchQueue.main.async { model.showWelcomeIfNeeded() }
    }

    var body: some Scene {
        MenuBarExtra(isInserted: $showInMenuBar) {
            MenuView()
                .environment(model)
        } label: {
            MenuBarIcon(model: model)
        }
        .menuBarExtraStyle(.window)
    }
}

/// With the menu bar icon hidden there is no other way in, so opening Syncliko again shows Settings,
/// as does launching it by hand. A launch at login stays quiet.
final class AppDelegate: NSObject, NSApplicationDelegate {
    static let showInMenuBarKey = "showInMenuBar"
    private var launchedAtLogin = false

    func applicationWillFinishLaunching(_ notification: Notification) {
        // The open-application event carries "launched as login item" when the system starts us at login.
        let event = NSAppleEventManager.shared().currentAppleEvent
        launchedAtLogin = event?.eventID == AEEventID(kAEOpenApplication)
            && event?.paramDescriptor(forKeyword: AEKeyword(keyAEPropData))?.enumCodeValue == OSType(keyAELaunchedAsLogInItem)
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        #if DEBUG
        if Snapshot.directory != nil { return }
        #endif
        let shown = UserDefaults.standard.object(forKey: Self.showInMenuBarKey) as? Bool ?? true
        if !shown, !launchedAtLogin {
            MainActor.assumeIsolated { WindowPresenter.shared.showSettings() }
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        MainActor.assumeIsolated { WindowPresenter.shared.showSettings() }
        return false
    }
}

/// Hosts the pairing and Settings windows. A menu-bar app has no main window, so pairing requests, the
/// first-run welcome and Settings open their windows directly — even when the popover is closed or the
/// menu bar icon is hidden. (SwiftUI's Settings scene can only be opened from a visible view.)
@MainActor
final class WindowPresenter {
    static let shared = WindowPresenter()
    private var window: NSWindow?
    private var settingsWindow: NSWindow?
    private weak var model: AppModel?

    /// Settings in the standard Mac form: General, Devices and About as toolbar tabs.
    func showSettings() {
        guard let model else { return }
        if settingsWindow == nil {
            let tabs = NSTabViewController()
            tabs.tabStyle = .toolbar
            func tab<V: View>(_ title: String.LocalizationValue, _ symbol: String, _ view: V) {
                let host = NSHostingController(rootView: view.environment(model).frame(width: 500, height: 420))
                host.title = String(localized: title)  // becomes the window title, as in System Settings
                let item = NSTabViewItem(viewController: host)
                item.label = host.title ?? ""
                item.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
                tabs.addTabViewItem(item)
            }
            tab("General", "gearshape", GeneralSettings())
            tab("Devices", "smartphone", DevicesSettings())
            tab("About", "info.circle", AboutSettings())
            let window = NSWindow(contentViewController: tabs)
            // Content runs under the toolbar, as in SwiftUI's own Settings window: the forms inset themselves once.
            window.styleMask = [.titled, .closable, .fullSizeContentView]
            window.toolbarStyle = .preference
            window.isReleasedWhenClosed = false
            window.center()
            settingsWindow = window
        }
        NSApp.activate()
        settingsWindow?.makeKeyAndOrderFront(nil)
    }

    #if DEBUG
    func selectSettingsTab(_ index: Int) {
        (settingsWindow?.contentViewController as? NSTabViewController)?.selectedTabViewItemIndex = index
    }
    #endif

    func attach(_ model: AppModel) {
        guard self.model == nil else { return }
        self.model = model
        model.presentPairingWindow = { [weak self] in self?.showPairing() }
    }

    func showPairing() {
        guard let model else { return }
        if window == nil {
            let host = NSHostingController(rootView: PairingView(onClose: { [weak self] in self?.window?.close() }).environment(model))
            let window = NSWindow(contentViewController: host)
            window.styleMask = [.titled, .closable, .fullSizeContentView]
            window.titlebarAppearsTransparent = true
            window.titleVisibility = .hidden
            window.isMovableByWindowBackground = true
            window.isReleasedWhenClosed = false
            window.title = String(localized: "Add a Device")
            window.center()
            NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: window, queue: .main) { _ in
                MainActor.assumeIsolated {
                    if model.pendingPairing == nil { model.endPairing() }
                }
            }
            self.window = window
        }
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
    }
}

/// Menu bar glyph: the Syncliko S from brand/mark.svg, drawn as a template image so it reads like an
/// SF Symbol on any menu bar. Solid while a device is connected, hollow (the S outlined) otherwise; it
/// dims while paused. On every synced clip a short animation runs along the S: the letter dims and a
/// bright segment travels through it, top to bottom when the Mac sends, bottom to top when it receives.
private struct MenuBarIcon: View {
    let model: AppModel
    @State private var flow = GlyphFlow()

    var body: some View {
        Image(nsImage: Self.glyph(connected: model.isConnected, flow: flow.progress, outgoing: flow.outgoing))
            .opacity(model.isPaused ? 0.45 : 1)
            .accessibilityLabel("Syncliko")
            .onChange(of: model.activityPulse) {
                flow.play(outgoing: model.lastActivity == .sent)
            }
    }

    /// The S's centre line on the mark's 100-unit grid, from the top terminal to the bottom one.
    private static let centreLine: CGPath = {
        let line = CGMutablePath()
        line.move(to: CGPoint(x: 66, y: 22))
        line.addLine(to: CGPoint(x: 50, y: 22))
        line.addArc(center: CGPoint(x: 50, y: 36), radius: 14, startAngle: -.pi / 2, endAngle: .pi / 2, clockwise: true)
        line.addArc(center: CGPoint(x: 50, y: 64), radius: 14, startAngle: -.pi / 2, endAngle: .pi / 2, clockwise: false)
        line.addLine(to: CGPoint(x: 34, y: 78))
        return line
    }()

    /// `flow` is the animation progress (0…1) or nil when idle.
    static func glyph(connected: Bool, flow: Double? = nil, outgoing: Bool = true) -> NSImage {
        let image = NSImage(size: NSSize(width: 14, height: 18), flipped: true) { _ in
            guard let ctx = NSGraphicsContext.current?.cgContext else { return false }
            // Grid → points: the S is 16 pt tall, centred in the image.
            let k: CGFloat = 0.215
            ctx.translateBy(x: 7, y: 9)
            ctx.scaleBy(x: k, y: k)
            ctx.translateBy(x: -50, y: -50)
            ctx.setLineCap(.butt)
            ctx.setStrokeColor(NSColor.black.cgColor)

            guard connected else {
                ctx.addPath(centreLine.copy(strokingWithWidth: 15, lineCap: .butt, lineJoin: .miter, miterLimit: 4))
                ctx.setLineWidth(1.1 / k)
                ctx.strokePath()
                return true
            }
            guard let p = flow else {
                ctx.addPath(centreLine)
                ctx.setLineWidth(15)
                ctx.strokePath()
                return true
            }
            // The letter fades to a dim track while the segment runs, then fills back in.
            let track = 1 - 0.7 * min(1, p / 0.12) * min(1, (1 - p) / 0.22)
            ctx.setAlpha(track)
            ctx.addPath(centreLine)
            ctx.setLineWidth(15)
            ctx.strokePath()
            // A segment 40% of the letter long, eased through it from one end to the other.
            let run = min(1, p / 0.82)
            let eased = run < 0.5 ? 2 * run * run : 1 - pow(-2 * run + 2, 2) / 2
            let length = 0.4
            let head = eased * (1 + length), tail = head - length
            var from = max(0, tail), to = min(1, head)
            if !outgoing { (from, to) = (1 - to, 1 - from) }
            if to > from {
                let segment = Path(centreLine).trimmedPath(from: from, to: to).cgPath
                ctx.setAlpha(1)
                ctx.addPath(segment)
                ctx.setLineWidth(15)
                ctx.strokePath()
            }
            return true
        }
        image.isTemplate = true
        return image
    }
}

/// Drives the menu bar glyph's sync animation frame by frame: a menu bar label is rendered into an
/// image, so SwiftUI animations don't reach it, but swapping the image at 60 fps does.
@MainActor @Observable
final class GlyphFlow {
    private(set) var progress: Double?
    private(set) var outgoing = true
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var start = Date()
    private let duration: TimeInterval = 0.7

    func play(outgoing: Bool) {
        self.outgoing = outgoing
        start = Date()
        progress = 0
        timer?.invalidate()
        let timer = Timer(timeInterval: 1.0 / 60, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func tick() {
        let t = Date().timeIntervalSince(start) / duration
        if t >= 1 {
            timer?.invalidate()
            timer = nil
            progress = nil
        } else {
            progress = t
        }
    }
}
