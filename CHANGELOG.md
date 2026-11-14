# Changelog

Each release on [GitHub Releases](https://github.com/kdanet/syncliko/releases) carries `Syncliko.dmg` (macOS),
`Syncliko.apk` (Android) and `SHA256SUMS`. `scripts/release.sh` takes the release notes from the matching section
below.

## 0.3.3 — 2026-11-15

**Android**

- The welcome and pairing screens were hard to read in the dark theme: their headings were black. They follow
  the theme now.
- The pairing screens use the Syncliko cobalt instead of the old violet, and the welcome screen shows the app
  icon.
- The pairing steps name the Mac menu item as it is now: Add Device.

**Mac**

- No changes; the version number moves with Android.

## 0.3.2 — 2026-11-14

The first public release.

**Both apps**

- A shared clipboard for an Android phone and a Mac: text, links and images up to 20 MB, in both directions.
- The devices talk directly over Wi-Fi (Bonjour) or Tailscale. TLS 1.3 with mutual authentication and pinned
  certificates, paired once with a QR code and a 6-digit check code. No account, no servers, no analytics.
- Everything you copy is synced, passwords and 2FA codes included. Copies a password manager marks as secret keep
  that mark on the other device.
- A copy made while the other device was away is delivered when it reconnects, if it's under 2 minutes old.
- New icon: a geometric S, one half for each device.

**Android**

- Three ways to send: Standard (an accessibility service notices Copy), Pro (follows Android's clipboard log over
  ADB, catches every app) and Manual (Send to Mac, Share, the Quick Settings tile).
- No permanent notification while the accessibility service is on.
- Easy on the battery: the connection closes 30 s after the screen turns off and comes back in about 0.2 s when it
  turns on.
- Signed with the Syncliko release key, so Obtainium can keep it up to date. If you installed a build from before
  this release, uninstall it first: it was signed with a different key.

**Mac**

- Redesigned menu: your devices and their state, recent clips, and the usual Settings and Quit rows.
- The menu bar icon animates on every synced clip, in the direction it went.
- Checks GitHub Releases for new versions once a day (Settings → Updates).
- Signed with a Developer ID, notarized by Apple, installed from a disk image.
