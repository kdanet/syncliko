import AppKit
import SwiftUI

@main
struct SynclikoApp: App {
    @State private var model: AppModel

    init() {
        #if DEBUG
        if Snapshot.directory != nil {
            let preview = Snapshot.makeModel()
            _model = State(initialValue: preview)
            DispatchQueue.main.async { Snapshot.run(preview) }
            return
        }
        #endif
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        MenuBarExtra {
            MenuView()
                .environment(model)
                .onAppear { WindowPresenter.shared.attach(model) }
        } label: {
            MenuBarIcon(model: model)
                .task {
                    WindowPresenter.shared.attach(model)
                    model.showWelcomeIfNeeded()
                }
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView().environment(model)
        }
    }
}

/// Hosts the pairing window. A menu-bar app has no main window, so pairing requests and the first-run
/// welcome open this window directly — even when the popover is closed.
@MainActor
final class WindowPresenter {
    static let shared = WindowPresenter()
    private var window: NSWindow?
    private weak var model: AppModel?

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
