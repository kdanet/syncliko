#!/bin/zsh
# Builds a complete Syncliko release into dist/release/:
#   Syncliko.apk   Android, signed with the release key (~/.config/syncliko, password in the Keychain)
#   Syncliko.dmg   macOS, Developer ID–signed, notarized and stapled (app and disk image)
#   SHA256SUMS     checksums of both
#   notes.md       the CHANGELOG.md section for this version plus verification details
# The asset names never change, so https://github.com/kdanet/syncliko/releases/latest/download/<name>
# always serves the newest build (the site links there), and Obtainium finds one APK per release.
#
#   scripts/release.sh            build and verify only
#   scripts/release.sh --publish  also tag vX.Y.Z, push, create the GitHub release and deploy the site
set -euo pipefail
ROOT=${0:A:h:h}
cd "$ROOT"
die() { print -u2 "✗ $*"; exit 1; }
step() { print "\n▸ $*"; }

PUBLISH=0
[[ "${1:-}" == "--publish" ]] && PUBLISH=1

VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' android/app/build.gradle.kts)
MAC_VERSION=$(sed -n 's/^ *MARKETING_VERSION: "\(.*\)"/\1/p' mac/project.yml)
[[ -n "$VERSION" && "$VERSION" == "$MAC_VERSION" ]] || die "versions differ: Android $VERSION, Mac $MAC_VERSION"
grep -q "^## $VERSION" CHANGELOG.md || die "CHANGELOG.md has no '## $VERSION' section"
grep -q "v$VERSION" site/index.html || die "site/index.html doesn't mention v$VERSION"
if (( PUBLISH )); then
  [[ -z "$(git status --porcelain)" ]] || die "commit or stash your changes first"
  ! git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || die "tag v$VERSION already exists"
  gh auth status >/dev/null 2>&1 || die "gh isn't signed in"
fi
OUT=dist/release
rm -rf "$OUT"
mkdir -p "$OUT"

step "Android $VERSION"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export SYNCLIKO_KEYSTORE="$HOME/.config/syncliko/syncliko-release.jks"
export SYNCLIKO_KEY_ALIAS=syncliko
[[ -f "$SYNCLIKO_KEYSTORE" ]] || die "release keystore not found at $SYNCLIKO_KEYSTORE"
SYNCLIKO_KEYSTORE_PASSWORD=$(security find-generic-password -s syncliko-android-release -a release -w) \
  || die "no 'syncliko-android-release' password in the Keychain"
export SYNCLIKO_KEYSTORE_PASSWORD
(cd android && ./gradlew -q --no-configuration-cache :core:protocol:test :core:transport:test :app:assembleRelease)
unset SYNCLIKO_KEYSTORE_PASSWORD
APK=android/app/build/outputs/apk/release/app-release.apk
APKSIGNER=$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)
CERT=$("$APKSIGNER" verify --print-certs "$APK" | awk -F': ' '/Signer #1 certificate SHA-256 digest/ {print $2}')
EXPECTED="665809924026afc91a0c5858190b1ec50e0d316cbbb8be8b5196abeeed9515c5"  # SHA-256 of the release certificate
[[ "$CERT" == "$EXPECTED" ]] || die "APK is signed with $CERT, expected the release key $EXPECTED"
cp "$APK" "$OUT/Syncliko.apk"
print "  signed with the release key ✓"

step "macOS $VERSION"
(cd mac/Packages/SynclikoCore && swift test -q 2>&1 | tail -1)
CONFIG=Release mac/build.sh
mac/notarize.sh mac/build/Syncliko.app
mac/package.sh
DMG="dist/Syncliko-$VERSION.dmg"
mac/notarize.sh "$DMG"
cp "$DMG" "$OUT/Syncliko.dmg"

step "Checksums and notes"
(cd "$OUT" && shasum -a 256 Syncliko.apk Syncliko.dmg > SHA256SUMS)
{
  awk -v v="## $VERSION" '$0 ~ "^"v {on=1; next} on && /^## / {exit} on' CHANGELOG.md
  print "\n### Install\n"
  print "- **Mac** (macOS 26+, Apple silicon): open \`Syncliko.dmg\` and drag Syncliko to Applications. Signed with a Developer ID and notarized by Apple."
  print "- **Android** (12+): install \`Syncliko.apk\`. To get updates automatically, add https://github.com/kdanet/syncliko to [Obtainium](https://obtainium.imranr.dev)."
  print "\n### Verify\n"
  print "APK signing certificate SHA-256: \`$(echo $CERT | sed 's/../&:/g; s/:$//' | tr a-f A-F)\`\n"
  print '```'; cat "$OUT/SHA256SUMS"; print '```'
} > "$OUT/notes.md"
cat "$OUT/SHA256SUMS"

if (( PUBLISH )); then
  step "Publishing v$VERSION"
  git tag -a "v$VERSION" -m "Syncliko $VERSION"
  git push origin HEAD "v$VERSION"
  gh release create "v$VERSION" "$OUT/Syncliko.apk" "$OUT/Syncliko.dmg" "$OUT/SHA256SUMS" \
    --title "Syncliko $VERSION" --notes-file "$OUT/notes.md" --verify-tag
  scripts/deploy-site.sh
else
  print "\nBuilt in $OUT. Run scripts/release.sh --publish to tag, upload and deploy."
fi
