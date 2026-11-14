import AppKit
import SwiftUI
import SynclikoProtocol

/// Content of the menu bar popover, laid out like the system's own menu bar panels (Wi-Fi, Bluetooth):
/// a header with the on/off switch, the paired devices, recent clips, and menu-style actions.
struct MenuView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, 14)
                .padding(.top, 14)
                .padding(.bottom, 12)
            notices
            if model.devices.isEmpty {
                emptyState
            } else {
                devicesSection
                historySection
            }
            Divider().padding(.horizontal, 14).padding(.top, 6)
            footer.padding(6)
        }
        .frame(width: 320)
        .onAppear { model.refreshPasteboardAccess() }
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            BrandMark(size: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text("Syncliko").font(.headline)
                HStack(spacing: 5) {
                    Circle().fill(statusColor).frame(width: 7, height: 7)
                    Text(model.statusLine)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .contentTransition(.opacity)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            Toggle("Sync", isOn: Binding(get: { !model.isPaused }, set: { model.isPaused = !$0 }))
                .toggleStyle(.switch)
                .labelsHidden()
                .controlSize(.small)
                .help(model.isPaused ? "Resume syncing" : "Pause syncing")
        }
        .animation(.smooth, value: model.statusLine)
    }

    private var statusColor: Color {
        if model.isPaused { return .orange }
        if case .failed = model.status { return .red }
        return model.isConnected ? .green : Color.secondary.opacity(0.5)
    }

    // MARK: Notices

    @ViewBuilder private var notices: some View {
        let failed: Bool = { if case .failed = model.status { true } else { false } }()
        if model.pendingPairing != nil || failed || model.pasteboardAccess != .alwaysAllow || model.updates.available != nil {
            VStack(spacing: 8) {
                if let candidate = model.pendingPairing {
                    PairingRequestCard(name: candidate.request.deviceName, code: candidate.code)
                }
                if failed {
                    NoticeCard(
                        icon: "network.slash",
                        tint: .red,
                        title: "Syncliko can’t accept connections",
                        message: model.startupError.map { LocalizedStringKey($0) } ?? "Allow Local Network access for Syncliko in System Settings → Privacy & Security, then reopen the app.",
                        action: nil
                    )
                }
                if let update = model.updates.available {
                    NoticeCard(
                        icon: "arrow.down.circle.fill",
                        tint: .accentColor,
                        title: "Syncliko \(update.version) is available",
                        message: "You have version \(model.updates.currentVersion). Download the new disk image and drag Syncliko to Applications.",
                        action: ("Download", model.updates.openDownload)
                    )
                }
                if model.pasteboardAccess != .alwaysAllow {
                    NoticeCard(
                        icon: model.pasteboardAccess == .alwaysDeny ? "hand.raised.fill" : "exclamationmark.triangle.fill",
                        tint: model.pasteboardAccess == .alwaysDeny ? .red : .orange,
                        title: model.pasteboardAccess == .alwaysDeny ? "Clipboard access is blocked" : "Allow clipboard access",
                        message: "In Privacy & Security → Paste from Other Apps, set Syncliko to “Always Allow”. Otherwise macOS asks on every copy.",
                        action: ("Open Settings", model.openPasteboardPrivacySettings)
                    )
                }
            }
            .padding(.horizontal, 10)
            .padding(.bottom, 10)
        }
    }

    // MARK: First run

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Connect your Android phone").font(.subheadline.weight(.semibold))
            VStack(alignment: .leading, spacing: 9) {
                Step(n: 1, text: "Install Syncliko on the phone")
                Step(n: 2, text: "Click Add Device and scan the code")
                Step(n: 3, text: "Copy on one device, paste on the other")
            }
            Button {
                showPairing()
            } label: {
                Text("Add Device…").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .padding(.horizontal, 14)
        .padding(.top, 2)
        .padding(.bottom, 10)
    }

    // MARK: Devices

    private var devicesSection: some View {
        VStack(alignment: .leading, spacing: 2) {
            SectionTitle("Devices")
            ForEach(model.deviceStates) { state in
                DeviceRow(state: state)
            }
            if model.waitingForPhone {
                Label("Your last copy will reach the phone when it reconnects", systemImage: "clock.arrow.circlepath")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 14)
                    .padding(.top, 4)
            }
        }
        .padding(.bottom, 8)
    }

    // MARK: Recent

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                SectionTitle("Recent")
                Spacer()
                if !model.history.isEmpty {
                    Button("Clear") { withAnimation(.smooth) { model.clearHistory() } }
                        .buttonStyle(.plain)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                }
            }
            if model.history.isEmpty {
                HStack(spacing: 10) {
                    Image(systemName: "doc.on.clipboard")
                        .font(.system(size: 13))
                        .foregroundStyle(.tertiary)
                        .frame(width: 26, height: 26)
                    Text("Copy something on your Mac or phone and it shows up here.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
            } else {
                HistoryList(items: model.history)
            }
        }
    }

    // MARK: Footer

    private var footer: some View {
        VStack(spacing: 0) {
            if !model.devices.isEmpty {
                MenuActionRow(title: "Add Device…", systemImage: "plus") { showPairing() }
            }
            MenuActionRow(title: "Settings…", systemImage: "gearshape", shortcut: "⌘,") {
                NSApp.activate()
                openSettings()
            }
            .keyboardShortcut(",", modifiers: .command)
            MenuActionRow(title: "Quit Syncliko", systemImage: "power", shortcut: "⌘Q") { NSApp.terminate(nil) }
                .keyboardShortcut("q", modifiers: .command)
        }
    }

    private func showPairing() {
        model.beginPairing()
        WindowPresenter.shared.showPairing()
    }
}

/// Section label in the system's menu panel style.
private struct SectionTitle: View {
    let title: LocalizedStringKey
    init(_ title: LocalizedStringKey) { self.title = title }

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 14)
            .padding(.top, 4)
            .padding(.bottom, 4)
    }
}

/// A paired device: a round icon that fills with the accent colour while it's connected.
private struct DeviceRow: View {
    let state: DeviceState

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: state.device.platform == .android ? "smartphone" : "laptopcomputer")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(state.online ? .white : .secondary)
                .frame(width: 28, height: 28)
                .background(state.online ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.quaternary), in: Circle())
            VStack(alignment: .leading, spacing: 1) {
                Text(state.device.name).font(.callout).lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .animation(.smooth, value: state.online)
    }

    private var subtitle: String {
        // Phones close the connection while their screen is off and reconnect when it turns on.
        guard state.online else { return String(localized: "Connects when its screen is on") }
        return state.route == .tailscale ? String(localized: "Connected · Tailscale") : String(localized: "Connected · Wi-Fi")
    }
}

/// Recent clips. The list grows with its content up to a limit and scrolls past it (a bare ScrollView
/// in a menu bar panel has no height of its own and would collapse to nothing).
private struct HistoryList: View {
    @Environment(AppModel.self) private var model
    let items: [HistoryItem]
    @State private var contentHeight: CGFloat = 0

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                ForEach(items) { item in
                    HistoryRow(item: item, onCopy: { model.restore(item) }, onDelete: { withAnimation(.smooth) { model.delete(item) } })
                        .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
                }
            }
            .padding(.horizontal, 6)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .frame(height: min(max(contentHeight, 44), 300))
        .scrollIndicators(.never)
        .scrollBounceBehavior(.basedOnSize)
        .animation(.smooth(duration: 0.25), value: items)
    }
}

/// A menu-style action row: icon, title, an optional shortcut hint, and a hover highlight.
private struct MenuActionRow: View {
    let title: LocalizedStringKey
    let systemImage: String
    var shortcut: String?
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 9) {
                Image(systemName: systemImage)
                    .font(.system(size: 13, weight: .medium))
                    .frame(width: 18)
                    .foregroundStyle(.secondary)
                Text(title)
                Spacer()
                if let shortcut {
                    Text(shortcut).foregroundStyle(.tertiary)
                }
            }
            .font(.callout)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(hovering ? AnyShapeStyle(.quaternary) : AnyShapeStyle(.clear), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
    }
}

private struct Step: View {
    let n: Int
    let text: LocalizedStringKey

    var body: some View {
        HStack(spacing: 10) {
            Text("\(n)")
                .font(.caption.weight(.bold))
                .frame(width: 20, height: 20)
                .background(.tint.opacity(0.15), in: Circle())
                .foregroundStyle(.tint)
            Text(text).font(.callout).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct PairingRequestCard: View {
    @Environment(AppModel.self) private var model
    let name: String
    let code: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("\(name) wants to pair", systemImage: "smartphone")
                .font(.callout.weight(.semibold))
            Text("Code on the phone: \(code)")
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
            HStack {
                Button("Decline") { model.resolvePairing(accept: false) }
                Spacer()
                Button("Pair") { model.resolvePairing(accept: true) }.buttonStyle(.borderedProminent)
            }
            .controlSize(.small)
        }
        .padding(10)
        .background(.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct NoticeCard: View {
    let icon: String
    let tint: Color
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    let action: (LocalizedStringKey, () -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon).foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.callout.weight(.semibold))
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let action {
                    Button(action.0, action: action.1).controlSize(.small)
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint.opacity(0.1), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

private struct HistoryRow: View {
    let item: HistoryItem
    let onCopy: () -> Void
    let onDelete: () -> Void
    @State private var hovering = false
    @State private var copied = false
    @State private var thumbnail: NSImage?

    var body: some View {
        Button {
            onCopy()
            copied = true
            Task { try? await Task.sleep(for: .seconds(1.2)); copied = false }
        } label: {
            HStack(alignment: .center, spacing: 10) {
                preview
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.callout)
                        .lineLimit(2)
                        .truncationMode(.tail)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    HStack(spacing: 4) {
                        Image(systemName: item.direction == .received ? "arrow.down.left" : "arrow.up.right")
                        Text(subtitle)
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
                if copied {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).transition(.scale.combined(with: .opacity))
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(hovering ? AnyShapeStyle(.quaternary) : AnyShapeStyle(.clear), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .animation(.snappy(duration: 0.15), value: hovering)
        .animation(.bouncy, value: copied)
        .help("Copy again")
        .contextMenu {
            Button("Copy") { onCopy() }
            Button("Delete", role: .destructive) { onDelete() }
        }
        .task(id: item.id) {
            guard item.clip.kind == .image, thumbnail == nil else { return }
            let data = item.clip.content
            thumbnail = await Task.detached { Self.makeThumbnail(data) }.value
        }
    }

    /// Decodes a small thumbnail once instead of the full image on every render.
    private nonisolated static func makeThumbnail(_ data: Data) -> NSImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceThumbnailMaxPixelSize: 96,
              ] as CFDictionary) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
    }

    @ViewBuilder private var preview: some View {
        switch item.clip.kind {
        case .image:
            if let thumbnail {
                Image(nsImage: thumbnail)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: 36, height: 36)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            } else {
                icon("photo")
            }
        case .url:
            icon("link")
        case .text:
            icon("text.alignleft")
        }
    }

    private func icon(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(.secondary)
            .frame(width: 36, height: 36)
            .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
    }

    private var title: String {
        switch item.clip.kind {
        case .image:
            let size = ByteCountFormatter.string(fromByteCount: Int64(item.clip.content.count), countStyle: .file)
            return String(localized: "Image · \(size)")
        default:
            return (item.clip.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: "\n", with: " ")
        }
    }

    private var subtitle: String {
        let time = Date().timeIntervalSince(item.date) < 60
            ? String(localized: "just now")
            : item.date.formatted(.relative(presentation: .named, unitsStyle: .abbreviated))
        if let peer = item.peerName {
            return item.direction == .received ? String(localized: "From \(peer) · \(time)") : String(localized: "To \(peer) · \(time)")
        }
        return time
    }
}
