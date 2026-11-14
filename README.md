# Syncliko

Universal-Clipboard-style sync between Android and macOS over the local network or Tailscale.
Fully native: Swift/SwiftUI on the Mac, Kotlin/Compose on Android. Website: https://kdanet.github.io/syncliko/

## Install

- **Mac** (macOS 26+, Apple silicon): download
  [`Syncliko.dmg`](https://github.com/kdanet/syncliko/releases/latest/download/Syncliko.dmg), open it and drag Syncliko
  to Applications. It's signed with a Developer ID and notarized, and checks this repository for new versions.
- **Android** (12+): download [`Syncliko.apk`](https://github.com/kdanet/syncliko/releases/latest/download/Syncliko.apk),
  or add `https://github.com/kdanet/syncliko` to [Obtainium](https://obtainium.imranr.dev) to get every update
  automatically. Release APKs are signed with one key; its certificate SHA-256 is
  `66:58:09:92:40:26:AF:C9:1A:0C:58:58:19:0B:1E:C5:0E:0D:31:6C:BB:B8:BE:8B:51:96:AB:EE:ED:95:15:C5`.

See [CHANGELOG.md](CHANGELOG.md) for what changed.

## Source

| Path | What |
|---|---|
| [`PROTOCOL.md`](PROTOCOL.md) | Wire protocol v1 (TLS 1.3 mutual auth with pinned certs, framing, pairing) |
| [`protocol/vectors.json`](protocol/vectors.json) | Shared test vectors. The Swift and Kotlin implementations must both pass them |
| [`mac/Packages/SynclikoCore`](mac/Packages/SynclikoCore) | Swift package: `SynclikoProtocol` (pure), `SynclikoTransport` (Network.framework), `syncliko-probe` CLI |
| [`mac/SynclikoMac`](mac/SynclikoMac) | Menu bar app (SwiftUI `MenuBarExtra` + AppKit pasteboard bridge) |
| [`android/core/protocol`](android/core/protocol) | Kotlin port of the protocol (pure JVM), tested against the shared vectors |
| [`android/core/transport`](android/core/transport) | Kotlin TLS client: pinning, happy-eyeballs race over endpoints, reconnect with backoff |
| [`android/app`](android/app) | Android app (Compose, Material You): foreground service, Keystore identity, capture entry points |
| [`site`](site) | The website (GitHub Pages) |
| [`brand`](brand) | The logo and the script that renders every icon from it |
| [`scripts`](scripts) | `release.sh` (build, sign, notarize, publish) and `deploy-site.sh` |

## Android

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug                         # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:protocol:test :core:transport:test   # JVM tests (vectors, TLS loopback)
```

Or open `android/` in Android Studio.

- **Pairing:** scan the Mac's QR in the app, or with the Samsung camera (the `syncliko://pair` link opens the app).
  For the emulator: `adb shell "am start -a android.intent.action.VIEW -d '<invite url>'"`.
- **Mac → phone** works in the background through a `connectedDevice` foreground service.
- **Android 17:** the app asks for `ACCESS_LOCAL_NETWORK` before pairing. Without it Android blocks LAN access, and every
  Bonjour discovery opens a system device picker, so discovery runs only when the permission is granted.

### Phone → Mac: capture modes

Since Android 10 an app can read the clipboard only while one of its windows has focus. Syncliko waits for a "user
copied" signal, briefly takes focus with an invisible 1×1 overlay window (or a transparent activity as a fallback),
reads the clipboard and gives focus back. All of this runs in [`capture/`](android/app/src/main/kotlin/com/syncliko/android/capture).

| Mode | Signal | Setup |
|---|---|---|
| **Pro** (ADB) | `ClipboardService` logs "Denying clipboard access to com.syncliko.android" on every copy; the app follows logcat | Once: `adb shell pm grant com.syncliko.android android.permission.READ_LOGS && adb shell appops set com.syncliko.android SYSTEM_ALERT_WINDOW allow` |
| **Standard** | Accessibility: a tap on "Copy", a "Copied" toast, the text-selection menu closing | Accessibility settings → "Syncliko automatic sending". On Samsung, sideloaded apps first need App info → ⋮ → Allow restricted settings |
| **Manual** | Selection menu "Send to Mac", Share, Quick Settings tile, notification button, opening the app | Nothing |

Both automatic modes can be on at the same time; signals are debounced, and overlapping reads queue instead of being dropped.

Known platform behavior:
- **Log access prompt (Pro):** on Android 13+ the system asks "Allow access to all device logs?" each time the app
  process starts, and only while the app is visible. The watcher therefore starts only when Syncliko is in the
  foreground. After a phone restart, Pro resumes once you open the app and tap Allow. Until then Standard (if on)
  covers the gap. The settings screen shows "Needs approval" and has a "Request access again" button.
- **"Syncliko pasted from your clipboard" toast:** Android 12+ shows it whenever an app reads clipboard content
  from another app. It can't be turned off by the app.
- On Android 15+ the text-selection toolbar is drawn by SystemUI and tapping "Copy" in it produces no click event.
  Standard mode arms when that menu appears and reads once it closes; if nothing was copied the hash is unchanged
  and nothing is sent.

Diagnostics: `adb shell setprop log.tag.SynclikoA11y DEBUG` logs accessibility signals;
`adb logcat -s SynclikoCapture SynclikoFocus SynclikoLogcat` shows every capture.

### Testing on the emulator

- `android/tools/copier` is a small "third-party app" for tests: `adb shell am broadcast -n com.syncliko.testcopier/.CopyReceiver --es text "…"`
  copies from the background, and its activity has a Copy button and selectable text.
- The emulator mirrors its clipboard into the Mac's, which breaks capture tests. Turn it off with
  `defaults write com.android.Emulator set.clipboardSharing -int 0` and restart the emulator.
- Give the AVD at least 4 cores (`hw.cpu.ncore` in `~/.android/avd/<name>.avd/config.ini`). With one core, focus
  changes take seconds and the system ANRs.

Interop test of the Kotlin client against the Swift server:
`swift run syncliko-probe serve --port 47999` in `mac/Packages/SynclikoCore`, then
`SYNCLIKO_INVITE='<url>' SYNCLIKO_HOST=127.0.0.1 ./gradlew :core:transport:test --tests '*interop*'`.

## Reliability and security

- **Pairing:** a QR code carries the Mac's certificate fingerprint and a single-use token (5 min). Both devices show
  the same 6-digit code derived from both fingerprints, and the user compares them before clicking Pair on the Mac.
  `syncliko://pair` links opened from outside the app (browser, camera) always ask for confirmation, and warn when
  they would replace the current Mac.
- **Server limits (Mac):** clip frames are ignored until a peer is authenticated. At most 2 partial clips per
  connection, and sizes are validated. Connections time out at 10 s without TLS and 15 s without hello/pairRequest.
  At most 16 sessions, 4 of them unauthenticated. A pending confirmation times out after 120 s.
- **Delivery:** clips are acknowledged. An unacknowledged clip is re-sent after reconnecting. A copy made while
  the other side was offline is delivered on reconnect if it is younger than 2 minutes (both directions).
- **Staying alive on Android:**
  - a `connectedDevice` foreground service;
  - a WorkManager watchdog every 15 min;
  - restart on boot and app update;
  - a reconnect plus ping/pong liveness probe on screen-on, unlock and network change;
  - a backoff that parks for 15 min on fatal auth errors;
  - a short wake lock while data arrives.

  **Screen off = no connection.** 30 s after the screen turns off the phone closes the connection and stops
  Bonjour discovery: no keepalives, no reconnect attempts and no multicast while it sleeps. When the screen
  turns on, even on the lock screen, it reconnects in about 0.2 s and fetches what was copied on the Mac in
  the last 2 minutes, so the clip is already there when you unlock.
  Measured on a Galaxy Z Fold 8: 0.07 mAh over 3 h 40 min of screen-off time, before this change.

  **No notification while the accessibility service is on.** The system binds an enabled accessibility
  service with `BIND_FOREGROUND_SERVICE_WHILE_AWAKE`, so the process stays at foreground priority while the
  screen is on. That is exactly when the connection is needed. In that case the accessibility service hosts
  sync, and `SyncService` (with its notification) stops. If the service is turned off or One UI puts it to
  sleep, `SyncService` takes over again. Problems the user must fix (the Mac forgot this phone) then come as
  a regular notification.

  In foreground-service mode (Pro-only or manual) the notification can still be hidden: Settings → Status
  notification → Hide opens the channel settings. If the user swipes it away (Android 14+), it isn't re-posted.

  The setup card guides the user through notifications, Unrestricted battery, Samsung "Never sleeping apps",
  vendor autostart screens (Xiaomi/HyperOS, Huawei/Honor, Oppo/OnePlus/Realme, Vivo) and Android 17's local
  network permission.
- **Privacy:**
  - Everything you copy is synced and kept in history, including passwords and 2FA codes. Copies that the source
    app marks as secret (`org.nspasteboard.ConcealedType` on the Mac, `EXTRA_IS_SENSITIVE` on Android) carry a
    `sensitive` flag, and the receiving side sets the same mark on its clipboard. Nothing is filtered.
  - The Mac reads the pasteboard only when a phone can receive it.
  - Pairing data is excluded from backups and device-to-device transfer.

## Mac

```bash
brew install xcodegen        # once
CONFIG=Release mac/build.sh  # → mac/build/Syncliko.app (omit CONFIG for a Debug build with the debug flags below)
open mac/build/Syncliko.app
```

`build.sh` generates the Xcode project from `mac/project.yml`, builds it, and signs the bundle: Release builds with
your "Developer ID Application" identity, a secure timestamp and the hardened runtime (what notarization needs),
Debug builds with "Apple Development". A stable signature means macOS doesn't keep asking for Keychain access.
`mac/notarize.sh <app or dmg>` notarizes and staples (see Releasing).
To edit in Xcode, run `xcodegen generate` in `mac/` and open `Syncliko.xcodeproj`.

`mac/package.sh` packs the built app into `dist/Syncliko-<version>.dmg`: the app and an Applications shortcut over a
"drag to install" background (`mac/dmg/`, rendered by `brand/render.sh`). It uses
[dmgbuild](https://github.com/dmgbuild/dmgbuild) (`pipx install dmgbuild`), which writes the window layout without
scripting Finder.

UI snapshots (Debug builds only): `build/DerivedData/Build/Products/Debug/Syncliko.app/Contents/MacOS/Syncliko
-SynclikoSnapshot /tmp/shots` renders the menu, settings and pairing window in light and dark on made-up data and
quits. Add `-AppleLanguages '(en)'` for English.

On first use:
- **Privacy & Security → Paste from Other Apps → Syncliko → Always Allow.** Otherwise macOS 15.4+ asks on every
  copy. The menu shows a warning card until this is set.
- Allow **Local Network** access when macOS asks.

### Tests

```bash
cd mac/Packages/SynclikoCore && swift test
```

This covers framing, chunking, pairing URLs, the shared vectors, and real mutual TLS on localhost (pairing, both
directions, a 700 KB chunked image, rejection of unknown devices, wrong pins and bad tokens).
After a protocol change, regenerate the vectors with `SYNCLIKO_WRITE_VECTORS=1 swift test`.

### `syncliko-probe` — a fake phone

```bash
cd mac/Packages/SynclikoCore
swift run syncliko-probe pair "syncliko://pair?..." --host 127.0.0.1   # pair with the app, stay connected
swift run syncliko-probe connect                                       # reconnect later
swift run syncliko-probe serve                                         # a fake Mac that auto-accepts pairing
```

Lines typed on stdin are sent as clips, and received clips are printed with their latency.
Debug builds of the app accept `--args -SynclikoAutoPair YES -SynclikoAutoAcceptPairing YES`. The app then opens a
pairing window on launch, logs the invite URL (`/usr/bin/log show --predicate 'subsystem == "com.syncliko.mac"'`)
and accepts pairing without the confirmation click.

## Stage 0 findings (Mac side)

- macOS 27 exposes `NSPasteboard.accessBehavior` (`default`/`ask`/`alwaysAllow`/`alwaysDeny`). Polling
  `changeCount` doesn't trigger the privacy alert, so the content is read only when it changes.
- `SecIdentityCreate` is public on macOS, so the TLS identity is built in memory from a CryptoKit key and a
  `swift-certificates` cert. The key is stored as a Keychain generic password, and no keychain identity is needed.
- TLS 1.3 + client certificate + ALPN `syncliko/1` interoperates with a non-Apple stack (LibreSSL `s_client`
  with an EC P-256 cert), which is close to what Android's Conscrypt does.
- Bonjour doesn't cross Tailscale. The invite and the `endpoints` message carry the Tailscale 100.x IP and the
  MagicDNS name (read from the `tailscale` CLI when it's installed).

## Brand

The mark is a geometric S of two half rings joined in the middle, one half per device: white over mint on a cobalt
tile. [`brand/mark.svg`](brand/mark.svg) is the source on a 100-unit grid, and
[`brand/render.sh`](brand/render.sh) renders the macOS app icon set and the site icons from it with headless Chrome.

- Android: the adaptive icon (`drawable/ic_launcher_foreground.xml`, `_background`), the themed monochrome layer
  (`ic_launcher_monochrome.xml`) and the small notification/tile icon (`ic_tile.xml`) are vectors with the same path.
- Mac menu bar (`MenuBarIcon` in `SynclikoApp.swift`): the S as a template image, solid while connected and hollow
  otherwise.
- Site: the logo in the header and footer is inline SVG (`.brand-mark` in `site/site.css`).

Colours: tile `#3A5CFF` → `#1F3BDB` (flat `#2D4DF2` on the site), top half `#FFFFFF`, bottom half `#9BF5C8`.

## Website

`site/index.html` is the static landing page in English, with light and dark themes, using system fonts and no external
requests. The technical details (pairing, encryption, network, a decoded protocol frame) are on `site/how-it-works.html`,
so the landing stays plain for everyone else. Both pages share `site/site.css` (tokens, type, header, footer, buttons)
and keep their own styles inline. Icons are `site/icon-{32,64,180,512}.png`.

GitHub Pages serves the `gh-pages` branch at https://kdanet.github.io/syncliko/. `scripts/deploy-site.sh` copies
`site/` there as a single force-pushed commit (`scripts/release.sh --publish` runs it too).

The download buttons point at the latest GitHub release
(`https://github.com/kdanet/syncliko/releases/latest/download/Syncliko.apk` and `…/Syncliko.dmg`), so each
release must carry assets with exactly those names. Update the version and sizes in the Install section when they
change.

## Releasing

One-time setup on the release Mac:

- **Developer ID Application** certificate in the login keychain (`security find-identity -v -p codesigning`).
- Notarization credentials, saved in the Keychain under the name `syncliko-notary`:
  `xcrun notarytool store-credentials syncliko-notary --apple-id <Apple ID> --team-id <Team ID>`
  (it asks for an app-specific password from account.apple.com).
- The Android release key at `~/.config/syncliko/syncliko-release.jks`, its password in the Keychain as the generic
  password `syncliko-android-release`. **Back up both**: without this key no future APK can update an installed one.
- `pipx install dmgbuild` and a signed-in `gh`.

Then bump `versionCode`/`versionName` in `android/app/build.gradle.kts` and `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`
in `mac/project.yml`, add a `## x.y.z` section to `CHANGELOG.md`, update the version on the site, commit, and run:

```bash
scripts/release.sh            # tests, builds, signs, notarizes → dist/release/ (Syncliko.apk, Syncliko.dmg, SHA256SUMS, notes.md)
scripts/release.sh --publish  # the same, then tags vX.Y.Z, pushes, creates the GitHub release and deploys the site
```

The script refuses to publish if the versions disagree, the changelog has no section for the version, the tree isn't
clean, or the APK isn't signed with the release key.

**Updates.** The Mac app reads `releases/latest` from the GitHub API once a day and offers the new disk image.
Obtainium tracks the same releases for Android: one APK per release, a higher `versionCode` each time, the same
signing key, and the tag `v<versionName>`.

## License

[MIT](LICENSE)
