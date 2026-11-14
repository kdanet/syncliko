import AppKit
import Observation

/// Looks for a newer Syncliko on GitHub Releases. It fetches only the public "latest release" feed of the
/// repository; the request carries nothing but the app version in its User-Agent.
/// Automatic checks run at most once a day (a failed one is retried after a few hours); the menu and
/// Settings show the result, and the download link points at the release's disk image.
@MainActor
@Observable
final class UpdateChecker {
    struct Update: Equatable {
        let version: String
        let page: URL
        let download: URL?
    }

    static let repository = "kdanet/syncliko"
    static var releasesPage: URL { URL(string: "https://github.com/\(repository)/releases")! }

    private(set) var available: Update?
    private(set) var lastChecked: Date?
    private(set) var checking = false
    private(set) var failed = false

    var automatic: Bool {
        didSet {
            guard enabled else { return }
            UserDefaults.standard.set(automatic, forKey: "checkForUpdates")
            if automatic { Task { await checkIfDue() } }
        }
    }

    let currentVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0"
    /// False in UI previews: no network, no saved settings.
    private let enabled: Bool
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var lastAttempt = Date.distantPast

    init(enabled: Bool = true) {
        self.enabled = enabled
        automatic = enabled ? (UserDefaults.standard.object(forKey: "checkForUpdates") as? Bool ?? true) : false
        lastChecked = enabled ? UserDefaults.standard.object(forKey: "lastUpdateCheck") as? Date : nil
        guard enabled else { return }
        // Shortly after launch, then hourly; each tick checks only if a check is due.
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(20))
            await self.checkIfDue()
        }
        let timer = Timer(timeInterval: 3600, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { Task { await self?.checkIfDue() } }
        }
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    private func checkIfDue() async {
        guard automatic else { return }
        let dayAgo = Date().addingTimeInterval(-24 * 3600)
        guard (lastChecked ?? .distantPast) < dayAgo, lastAttempt < Date().addingTimeInterval(-4 * 3600) else { return }
        await check()
    }

    func check() async {
        guard enabled, !checking else { return }
        checking = true
        lastAttempt = Date()
        defer { checking = false }
        var request = URLRequest(url: URL(string: "https://api.github.com/repos/\(Self.repository)/releases/latest")!)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Syncliko/\(currentVersion) (macOS)", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { failed = true; return }
            let release = try JSONDecoder().decode(Release.self, from: data)
            failed = false
            lastChecked = Date()
            UserDefaults.standard.set(lastChecked, forKey: "lastUpdateCheck")
            let version = release.tagName.hasPrefix("v") ? String(release.tagName.dropFirst()) : release.tagName
            if !release.draft, !release.prerelease, Self.isVersion(version, newerThan: currentVersion) {
                let dmg = release.assets.first { $0.name.hasSuffix(".dmg") }?.browserDownloadURL
                available = Update(version: version, page: release.htmlURL, download: dmg)
            } else {
                available = nil
            }
            log.notice("update check: latest \(version, privacy: .public), current \(self.currentVersion, privacy: .public)")
        } catch {
            failed = true
            log.error("update check failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    func openDownload() {
        guard let update = available else { return }
        NSWorkspace.shared.open(update.download ?? update.page)
    }

    /// Compares dotted numeric versions ("0.10.1" > "0.9.3"); missing parts count as zero.
    nonisolated static func isVersion(_ a: String, newerThan b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }, y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let l = i < x.count ? x[i] : 0, r = i < y.count ? y[i] : 0
            if l != r { return l > r }
        }
        return false
    }

    #if DEBUG
    func setPreview(_ update: Update?, checked: Date? = nil) {
        available = update
        lastChecked = checked
    }
    #endif

    private struct Release: Decodable {
        struct Asset: Decodable {
            let name: String
            let browserDownloadURL: URL
            enum CodingKeys: String, CodingKey { case name, browserDownloadURL = "browser_download_url" }
        }
        let tagName: String
        let htmlURL: URL
        let draft: Bool
        let prerelease: Bool
        let assets: [Asset]
        enum CodingKeys: String, CodingKey { case tagName = "tag_name", htmlURL = "html_url", draft, prerelease, assets }
    }
}
