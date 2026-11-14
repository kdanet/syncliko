import SwiftUI
import SynclikoTransport

/// Window with the pairing QR code, the confirmation step (with a matching code), and the result.
struct PairingView: View {
    @Environment(AppModel.self) private var model
    var onClose: () -> Void = {}

    var body: some View {
        VStack(spacing: 18) {
            if let candidate = model.pendingPairing {
                confirm(candidate)
            } else if let pairing = model.pairing {
                invite(pairing)
            } else {
                finished
            }
        }
        .padding(.horizontal, 32)
        .padding(.top, 28)
        .padding(.bottom, 28)
        .frame(width: 400)
        .animation(.smooth, value: model.pairing)
        .animation(.smooth, value: model.pendingPairing)
        .animation(.smooth, value: model.pairingOutcome)
    }

    private func invite(_ pairing: PairingSession) -> some View {
        VStack(spacing: 18) {
            VStack(spacing: 8) {
                BrandMark(size: 44)
                    .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
                    .padding(.bottom, 4)
                Text("Add a Device").font(.title2.weight(.semibold))
                Text("Open Syncliko on your Android phone and scan this code. The Samsung camera works too.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            QRCodeView(text: pairing.invite.url.absoluteString)
                .padding(14)
                .background(.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .frame(width: 240, height: 240)
                .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
                .accessibilityLabel("Pairing QR code")
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let remaining = max(0, Int(pairing.expiresAt.timeIntervalSince(context.date)))
                Label("Code expires in \(remaining / 60):\(String(format: "%02d", remaining % 60))", systemImage: "timer")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            if model.pasteboardAccess != .alwaysAllow {
                Label("Next: allow Syncliko to paste from other apps", systemImage: "hand.raised")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
            DisclosureGroup("Connection details") {
                VStack(alignment: .leading, spacing: 4) {
                    LabeledContent("Port", value: "\(pairing.invite.port)")
                    LabeledContent("Addresses", value: pairing.invite.hosts.joined(separator: "\n"))
                    LabeledContent("This Mac", value: model.macFingerprintShort)
                }
                .font(.caption.monospaced())
                .textSelection(.enabled)
                .padding(.top, 4)
            }
            .font(.caption)
        }
        .transition(.opacity)
    }

    private func confirm(_ candidate: PairingCandidate) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "smartphone")
                .font(.system(size: 40, weight: .light))
                .foregroundStyle(.tint)
                .symbolEffect(.variableColor.iterative, options: .repeating)
            Text("Pair with “\(candidate.request.deviceName)”?")
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
            Text("Check that your phone shows the same code:")
                .foregroundStyle(.secondary)
            Text(candidate.code)
                .font(.system(size: 34, weight: .semibold, design: .rounded).monospacedDigit())
                .tracking(2)
                .textSelection(.enabled)
            Text("The phone will be able to read and change your Mac’s clipboard.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 12) {
                Button("Decline") { model.resolvePairing(accept: false) }
                    .controlSize(.large)
                Button("Pair") { model.resolvePairing(accept: true) }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .transition(.scale(scale: 0.95).combined(with: .opacity))
    }

    private var finished: some View {
        VStack(spacing: 14) {
            switch model.pairingOutcome {
            case .paired(let name):
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 48))
                    .foregroundStyle(.green)
                    .symbolEffect(.bounce, value: name)
                Text("Paired with \(name)").font(.title3.weight(.semibold))
                Text("Copy something on either device and paste it on the other.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            case .declined:
                outcome(icon: "xmark.circle", title: "Pairing declined")
            case .failed:
                outcome(icon: "exclamationmark.triangle", title: "The phone disconnected before pairing finished")
            case nil:
                outcome(icon: "qrcode", title: "The pairing code has expired")
            }
            HStack {
                if !(model.pairingOutcome.map { if case .paired = $0 { true } else { false } } ?? false) {
                    Button("Show New Code") { model.beginPairing() }
                }
                Button("Done") { onClose() }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        }
        .transition(.opacity)
    }

    private func outcome(icon: String, title: LocalizedStringKey) -> some View {
        VStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 44)).foregroundStyle(.secondary)
            Text(title).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
        }
    }
}
