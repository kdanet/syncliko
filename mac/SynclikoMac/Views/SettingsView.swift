import SwiftUI
import SynclikoTransport

struct SettingsView: View {
    var body: some View {
        TabView {
            Tab("General", systemImage: "gearshape") { GeneralSettings() }
            Tab("Devices", systemImage: "smartphone") { DevicesSettings() }
            Tab("About", systemImage: "info.circle") { AboutSettings() }
        }
        .frame(width: 500, height: 420)
    }
}

struct GeneralSettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Form {
            Section {
                Toggle("Sync clipboard", isOn: Binding(get: { !model.isPaused }, set: { model.isPaused = !$0 }))
                Toggle("Open at login", isOn: $model.launchAtLogin)
                if model.loginItemStatus == .requiresApproval {
                    Text("Approve Syncliko in System Settings → General → Login Items.")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            } footer: {
                Text("Everything you copy is synced, including passwords and codes.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            UpdatesSection(updates: model.updates)
            Section("Clipboard access") {
                LabeledContent("Paste from other apps") {
                    switch model.pasteboardAccess {
                    case .alwaysAllow: Label("Always allowed", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    case .alwaysDeny: Label("Denied", systemImage: "xmark.octagon.fill").foregroundStyle(.red)
                    default: Label("Asks every time", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    }
                }
                if model.pasteboardAccess != .alwaysAllow {
                    Button("Open Privacy Settings…") { model.openPasteboardPrivacySettings() }
                }
            }
            Section("This Mac") {
                LabeledContent("Name", value: model.deviceName)
                LabeledContent("Status", value: model.statusLine)
                if case .listening(let port) = model.status { LabeledContent("Port", value: "\(port)") }
                LabeledContent("Fingerprint") { Text(model.macFingerprintShort).font(.body.monospaced()).textSelection(.enabled) }
                if let error = model.startupError { Text(error).foregroundStyle(.red).font(.caption) }
            }
        }
        .formStyle(.grouped)
        .onAppear { model.refreshPasteboardAccess() }
    }
}

/// "Updates": the automatic check, a manual one, and what the last check found.
private struct UpdatesSection: View {
    @Bindable var updates: UpdateChecker

    var body: some View {
        Section("Updates") {
            Toggle("Check for updates automatically", isOn: $updates.automatic)
            LabeledContent {
                HStack(spacing: 8) {
                    if updates.checking { ProgressView().controlSize(.small) }
                    if updates.available != nil {
                        Button("Download…") { updates.openDownload() }
                    }
                    Button("Check Now") { Task { await updates.check() } }
                        .disabled(updates.checking)
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Version \(updates.currentVersion)")
                    Text(status).font(.caption).foregroundStyle(updates.available != nil ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.secondary))
                }
            }
        }
    }

    private var status: String {
        if let update = updates.available { return String(localized: "Version \(update.version) is available") }
        if updates.failed { return String(localized: "Couldn’t reach GitHub") }
        if let date = updates.lastChecked {
            return String(localized: "Up to date · checked \(date.formatted(.relative(presentation: .named)))")
        }
        return String(localized: "Not checked yet")
    }
}

struct DevicesSettings: View {
    @Environment(AppModel.self) private var model
    @State private var deviceToRemove: PairedDevice?

    var body: some View {
        Form {
            Section {
                if model.devices.isEmpty {
                    Text("No devices yet. Add your Android phone to start syncing.")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.deviceStates) { state in
                    HStack(spacing: 10) {
                        Image(systemName: state.device.platform == .android ? "smartphone" : "laptopcomputer")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundStyle(state.online ? .white : .secondary)
                            .frame(width: 30, height: 30)
                            .background(state.online ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.quaternary), in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(state.device.name)
                            Text(state.online
                                 ? String(localized: "Connected")
                                 : String(localized: "Added \(state.device.pairedAt.formatted(date: .abbreviated, time: .omitted))"))
                                .font(.caption)
                                .foregroundStyle(state.online ? .green : .secondary)
                        }
                        Spacer()
                        Button("Remove…") { deviceToRemove = state.device }
                            .controlSize(.small)
                    }
                    .help(Self.short(state.device.fingerprintHex))
                }
            } header: {
                Text("Paired devices")
            }
            Section {
                Button("Add Device…") {
                    model.beginPairing()
                    WindowPresenter.shared.showPairing()
                }
            }
        }
        .formStyle(.grouped)
        .confirmationDialog(
            "Remove \(deviceToRemove?.name ?? "")?",
            isPresented: Binding(get: { deviceToRemove != nil }, set: { if !$0 { deviceToRemove = nil } }),
            presenting: deviceToRemove
        ) { device in
            Button("Remove", role: .destructive) { model.remove(device) }
        } message: { _ in
            Text("The phone will stop syncing until you add it again.")
        }
    }

    private static func short(_ hex: String) -> String {
        stride(from: 0, to: min(16, hex.count), by: 4).map { i in
            String(hex[hex.index(hex.startIndex, offsetBy: i)..<hex.index(hex.startIndex, offsetBy: i + 4)])
        }.joined(separator: " ")
    }
}

struct AboutSettings: View {
    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "—"
        let build = info?["CFBundleVersion"] as? String ?? "—"
        return String(localized: "Version \(short) (\(build))")
    }

    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            BrandMark(size: 72)
                .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
            VStack(spacing: 4) {
                Text("Syncliko").font(.title2.weight(.semibold))
                Text(version).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
            }
            Text("A shared clipboard for your Android phone and your Mac, over Wi-Fi or Tailscale. No account, no cloud.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 320)
            Link("github.com/kdanet/syncliko", destination: URL(string: "https://github.com/kdanet/syncliko")!)
                .font(.callout)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(24)
    }
}
