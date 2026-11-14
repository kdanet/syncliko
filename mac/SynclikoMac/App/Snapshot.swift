#if DEBUG
import AppKit
import SwiftUI
import SynclikoProtocol
import SynclikoTransport

/// Debug builds only: renders every screen to PNG on made-up data and quits, for reviewing the UI
/// without real devices or real clipboard history:
///   Syncliko.app/Contents/MacOS/Syncliko -SynclikoSnapshot /tmp/shots [-AppleLanguages '(en)']
/// The preview model never starts the server, reads the Keychain or writes settings.
@MainActor
enum Snapshot {
    static var directory: String? { UserDefaults.standard.string(forKey: "SynclikoSnapshot") }

    static func makeModel() -> AppModel { AppModel(preview: [], history: []) }

    static func run(_ model: AppModel) {
        guard let directory else { return }
        let dir = URL(fileURLWithPath: directory, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        let fold = PairedDevice(fingerprint: Data(repeating: 0xA1, count: 32), deviceId: "android-fold", name: "Galaxy Z Fold 8",
                                platform: .android, pairedAt: Date(timeIntervalSinceNow: -12 * 86_400))
        let tablet = PairedDevice(fingerprint: Data(repeating: 0xB2, count: 32), deviceId: "android-tab", name: "Galaxy Tab S11",
                                  platform: .android, pairedAt: Date(timeIntervalSinceNow: -40 * 86_400))
        let history = [
            HistoryItem(clip: .text("https://table.io/r/8KX2-Q4", origin: "android"), direction: .received, peerName: fold.name, date: Date(timeIntervalSinceNow: -25)),
            HistoryItem(clip: .text("Pokrovka St 17, Moscow", origin: "mac"), direction: .sent, peerName: fold.name, date: Date(timeIntervalSinceNow: -6 * 60)),
            HistoryItem(clip: Clip.make(kind: .image, mime: "image/png", content: sampleImage(), origin: "android"), direction: .received, peerName: fold.name, date: Date(timeIntervalSinceNow: -50 * 60)),
            HistoryItem(clip: .text("Booking for Friday: table for 4 at 20:00, the café upstairs. Bring the flowers.", origin: "mac"), direction: .sent, peerName: fold.name, date: Date(timeIntervalSinceNow: -3 * 3600)),
        ]
        let invite = PairingInvite(deviceId: "mac-preview", deviceName: "MacBook Pro", fingerprint: Data(repeating: 0xC3, count: 32),
                                   token: Data(repeating: 0x5A, count: 16), port: Int(SynclikoProtocol.defaultPort),
                                   hosts: ["192.168.1.20", "100.101.102.103"])

        for dark in [true, false] {
            let suffix = dark ? "dark" : "light"
            model.setPreview(devices: [fold, tablet], history: history, pairing: .some(nil))
            model.previewOnline = [fold.fingerprintHex]
            render(MenuView(), model, "menu-connected-\(suffix)", dark: dark)
            model.previewOnline = []
            model.setPreview(devices: [fold], history: [])
            render(MenuView(), model, "menu-asleep-\(suffix)", dark: dark)
            model.setPreview(devices: [], history: [])
            render(MenuView(), model, "menu-new-\(suffix)", dark: dark)
            model.setPreview(devices: [fold], history: Array(history.prefix(2)))
            model.previewOnline = [fold.fingerprintHex]
            model.updates.setPreview(.init(version: "0.4.0", page: UpdateChecker.releasesPage, download: nil))
            render(MenuView(), model, "menu-update-\(suffix)", dark: dark)
            model.updates.setPreview(nil, checked: Date(timeIntervalSinceNow: -3 * 3600))

            model.setPreview(devices: [fold, tablet], history: history)
            model.previewOnline = [fold.fingerprintHex]
            let settings = CGSize(width: 500, height: 420)
            render(GeneralSettings(), model, "settings-general-\(suffix)", dark: dark, size: settings)
            render(DevicesSettings(), model, "settings-devices-\(suffix)", dark: dark, size: settings)
            render(AboutSettings(), model, "settings-about-\(suffix)", dark: dark, size: settings)
            model.setPreview(pairing: .some(PairingSession(invite: invite, expiresAt: Date(timeIntervalSinceNow: 272))))
            render(PairingView(), model, "pairing-\(suffix)", dark: dark)
        }
        exit(0)
    }

    private static func render<V: View>(_ view: V, _ model: AppModel, _ name: String, dark: Bool, size: CGSize? = nil) {
        let appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        let panel = dark ? Color(white: 0.16) : Color(white: 0.95)
        let host = NSHostingView(rootView: AnyView(view.environment(model).background(panel)))
        host.appearance = appearance
        let fitted = size ?? host.fittingSize
        host.frame = CGRect(origin: .zero, size: fitted)
        let window = KeyWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.appearance = appearance
        window.contentView = host
        window.setFrameOrigin(NSPoint(x: -20_000, y: -20_000))
        window.makeKeyAndOrderFront(nil)
        host.layoutSubtreeIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.4))
        guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { return }
        host.cacheDisplay(in: host.bounds, to: rep)
        window.orderOut(nil)

        guard let png = rep.representation(using: .png, properties: [:]) else { return }
        try? png.write(to: URL(fileURLWithPath: directory!).appendingPathComponent("\(name).png"))
    }

    /// Borderless windows can't become key by default, and controls in a non-key window draw dimmed.
    private final class KeyWindow: NSWindow {
        override var canBecomeKey: Bool { true }
    }

    private static func sampleImage() -> Data {
        let image = NSImage(size: NSSize(width: 160, height: 120), flipped: false) { rect in
            NSGradient(starting: NSColor(red: 1, green: 0.85, blue: 0.7, alpha: 1), ending: NSColor(red: 0.95, green: 0.55, blue: 0.35, alpha: 1))?.draw(in: rect, angle: 90)
            NSColor(red: 0.96, green: 0.64, blue: 0.32, alpha: 1).setFill()
            NSBezierPath(ovalIn: NSRect(x: 55, y: 25, width: 50, height: 46)).fill()
            NSBezierPath(ovalIn: NSRect(x: 58, y: 58, width: 44, height: 40)).fill()
            return true
        }
        guard let tiff = image.tiffRepresentation, let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) else { return Data() }
        return png
    }
}
#endif
