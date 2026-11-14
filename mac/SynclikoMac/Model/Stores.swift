import Foundation
import SynclikoProtocol
import SynclikoTransport
import SystemConfiguration

/// Paired devices, persisted as JSON in Application Support.
struct DeviceStore {
    private let url: URL

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Syncliko", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        url = dir.appendingPathComponent("devices.json")
    }

    func load() -> [PairedDevice] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([PairedDevice].self, from: data)) ?? []
    }

    func save(_ devices: [PairedDevice]) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? encoder.encode(devices).write(to: url, options: .atomic)
    }
}

enum LocalDevice {
    static var id: String {
        let key = "deviceId"
        if let existing = UserDefaults.standard.string(forKey: key) { return existing }
        let new = "mac-" + UUID().uuidString.lowercased()
        UserDefaults.standard.set(new, forKey: key)
        return new
    }

    /// The user-visible computer name ("Danil's MacBook Pro"). Fast, unlike `Host.current()`.
    static var name: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? "Mac"
    }

    static var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0"
    }
}

/// Recent clips, persisted across launches (Application Support/Syncliko/history), including clips
/// marked as sensitive. Contents live in one file per clip so the index stays small.
struct HistoryStore {
    private struct Entry: Codable {
        var header: ClipHeader
        var direction: HistoryItem.Direction
        var peerName: String?
        var date: Date
    }

    private let dir: URL
    private var index: URL { dir.appendingPathComponent("index.json") }
    /// One serial writer, so saves land in order.
    private static let writer = DispatchQueue(label: "syncliko.history", qos: .utility)

    init() {
        dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Syncliko/history", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private static func safeName(_ id: String) -> String? {
        // Clip ids come from peers: never let one become a path.
        id.range(of: #"^[A-Za-z0-9-]{1,64}$"#, options: .regularExpression) != nil ? id : nil
    }

    func load() -> [HistoryItem] {
        guard let data = try? Data(contentsOf: index),
              let entries = try? JSONDecoder().decode([Entry].self, from: data) else { return [] }
        return entries.compactMap { entry in
            guard let name = Self.safeName(entry.header.id),
                  let content = try? Data(contentsOf: dir.appendingPathComponent(name)) else { return nil }
            return HistoryItem(clip: Clip(header: entry.header, content: content), direction: entry.direction, peerName: entry.peerName, date: entry.date)
        }
    }

    func save(_ items: [HistoryItem]) {
        let dir = self.dir, index = self.index
        let snapshot = items
        Self.writer.async {
            var keep: Set<String> = ["index.json"]
            var entries: [Entry] = []
            for item in snapshot {
                guard let name = Self.safeName(item.clip.id) else { continue }
                keep.insert(name)
                let file = dir.appendingPathComponent(name)
                if !FileManager.default.fileExists(atPath: file.path) { try? item.clip.content.write(to: file, options: .atomic) }
                entries.append(Entry(header: item.clip.header, direction: item.direction, peerName: item.peerName, date: item.date))
            }
            try? JSONEncoder().encode(entries).write(to: index, options: .atomic)
            let files = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
            for file in files where !keep.contains(file) { try? FileManager.default.removeItem(at: dir.appendingPathComponent(file)) }
        }
    }
}
