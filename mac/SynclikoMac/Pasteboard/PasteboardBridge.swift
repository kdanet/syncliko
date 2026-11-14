import AppKit
import SynclikoProtocol
import UniformTypeIdentifiers

/// Watches NSPasteboard.general and writes incoming clips to it.
///
/// NSPasteboard has no change notifications, so we poll `changeCount` (cheap, and it never triggers
/// the macOS paste-privacy prompt). Contents are read only when the owner asks for them.
@MainActor
final class PasteboardBridge {
    nonisolated static let originType = NSPasteboard.PasteboardType("com.syncliko.origin")
    private nonisolated static let concealedType = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")

    /// Types that password managers use to mark a copy as a secret (see nspasteboard.org). Such copies
    /// are synced like any other; the flag travels with them so the receiving side can mark them too.
    private nonisolated static let sensitiveTypes: Set<NSPasteboard.PasteboardType> = [
        concealedType,
        .init("com.agilebits.onepassword"),
    ]

    private let pasteboard = NSPasteboard.general
    private var lastChangeCount: Int
    private var timer: Timer?
    private let deviceId: String
    private let readQueue = DispatchQueue(label: "syncliko.pasteboard.read", qos: .userInitiated)
    var onChange: (() -> Void)?

    init(deviceId: String) {
        self.deviceId = deviceId
        lastChangeCount = pasteboard.changeCount
    }

    var accessBehavior: NSPasteboard.AccessBehavior { pasteboard.accessBehavior }

    func start() {
        guard timer == nil else { return }
        lastChangeCount = pasteboard.changeCount
        let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
        timer.tolerance = 0.2
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Forget the current change count (after un-pausing) so the next copy is picked up normally.
    func resync() { lastChangeCount = pasteboard.changeCount }

    private func poll() {
        let count = pasteboard.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        onChange?()
    }

    /// Reads the current pasteboard off the main thread (Universal Clipboard content is fetched over
    /// the network on access; big images are re-encoded) and calls back on the main actor.
    func read(_ completion: @escaping @MainActor (Clip?) -> Void) {
        let deviceId = self.deviceId
        readQueue.async {
            let clip = Self.readClip(from: NSPasteboard.general, deviceId: deviceId)
            DispatchQueue.main.async { MainActor.assumeIsolated { completion(clip) } }
        }
    }

    private nonisolated static func readClip(from pasteboard: NSPasteboard, deviceId: String) -> Clip? {
        guard let types = pasteboard.types, !types.isEmpty else { return nil }
        let typeSet = Set(types)
        if typeSet.contains(originType) { return nil } // we wrote it
        let sensitive = !typeSet.isDisjoint(with: sensitiveTypes)

        let text = pasteboard.string(forType: .string)
        let imageTypes: Set<NSPasteboard.PasteboardType> = [.png, .tiff, .init(UTType.jpeg.identifier), .init(UTType.heic.identifier)]
        let firstIsImage = types.first.map { imageTypes.contains($0) } ?? false

        // Office apps, Notes and browsers put image renditions next to copied text; text wins unless
        // the copy really is an image (an image type is the first, most faithful representation).
        if let text, !text.isEmpty, !firstIsImage, !typeSet.contains(.fileURL) {
            return Clip.text(text, origin: deviceId, sensitive: sensitive)
        }
        if let image = readImage(pasteboard, types: typeSet, deviceId: deviceId) { return image }
        // Copied files: only images are supported for now (e.g. from Photos or Finder).
        if typeSet.contains(.fileURL),
           let url = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true])?.first as? URL,
           let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .image),
           let data = try? Data(contentsOf: url), data.count <= SynclikoProtocol.maxClipSize {
            return encodeImage(data, deviceId: deviceId)
        }
        if let text, !text.isEmpty, !typeSet.contains(.fileURL) { return Clip.text(text, origin: deviceId, sensitive: sensitive) }
        return nil
    }

    private nonisolated static func readImage(_ pasteboard: NSPasteboard, types: Set<NSPasteboard.PasteboardType>, deviceId: String) -> Clip? {
        if types.contains(.png), let png = pasteboard.data(forType: .png), png.count <= SynclikoProtocol.maxClipSize {
            return Clip.make(kind: .image, mime: "image/png", content: png, origin: deviceId)
        }
        for type in [NSPasteboard.PasteboardType(UTType.jpeg.identifier), .tiff, .init(UTType.heic.identifier)] where types.contains(type) {
            if let data = pasteboard.data(forType: type) { return encodeImage(data, deviceId: deviceId) }
        }
        return nil
    }

    private nonisolated static func encodeImage(_ data: Data, deviceId: String) -> Clip? {
        guard let rep = NSBitmapImageRep(data: data) else { return nil }
        if let png = rep.representation(using: .png, properties: [:]), png.count <= SynclikoProtocol.maxClipSize {
            return Clip.make(kind: .image, mime: "image/png", content: png, origin: deviceId)
        }
        // Very large screenshots/photos: fall back to JPEG.
        for quality in [0.9, 0.75, 0.6] {
            if let jpeg = rep.representation(using: .jpeg, properties: [.compressionFactor: quality]),
               jpeg.count <= SynclikoProtocol.maxClipSize {
                return Clip.make(kind: .image, mime: "image/jpeg", content: jpeg, origin: deviceId)
            }
        }
        return nil
    }

    /// Puts a clip received from another device on the pasteboard, tagged so we don't send it back.
    func write(_ clip: Clip) {
        guard let item = makeItem(clip, markOrigin: true) else { return }
        pasteboard.prepareForNewContents(with: [])
        pasteboard.writeObjects([item])
        lastChangeCount = pasteboard.changeCount
    }

    /// Puts a history entry back on the pasteboard as if the user copied it here,
    /// so the next poll picks it up and syncs it like any local copy.
    func restore(_ clip: Clip) {
        guard let item = makeItem(clip, markOrigin: false) else { return }
        pasteboard.clearContents()
        pasteboard.writeObjects([item])
    }

    private func makeItem(_ clip: Clip, markOrigin: Bool) -> NSPasteboardItem? {
        let item = NSPasteboardItem()
        switch clip.kind {
        case .text, .url:
            guard let text = clip.text else { return nil }
            item.setString(text, forType: .string)
            if clip.kind == .url, let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)) {
                item.setString(url.absoluteString, forType: .URL)
            }
        case .image:
            let type: NSPasteboard.PasteboardType = switch clip.header.mime {
            case "image/png": .png
            case "image/jpeg": .init(UTType.jpeg.identifier)
            case "image/heic": .init(UTType.heic.identifier)
            default: .init(UTType(mimeType: clip.header.mime)?.identifier ?? UTType.data.identifier)
            }
            item.setData(clip.content, forType: type)
            // Many apps only understand PNG/TIFF; add a PNG rendition for other formats.
            if type != .png, let rep = NSBitmapImageRep(data: clip.content), let png = rep.representation(using: .png, properties: [:]) {
                item.setData(png, forType: .png)
            }
        }
        if markOrigin {
            item.setString(clip.header.origin, forType: Self.originType)
            if clip.header.sensitive { item.setString("", forType: Self.concealedType) }
        }
        return item
    }
}
