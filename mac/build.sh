#!/bin/zsh
# Builds Syncliko.app into mac/build/Syncliko.app.
# Xcode builds unsigned; the finished bundle is then signed with your Developer ID (Release) or Apple
# Development (Debug) identity, or ad-hoc if none is found. A stable signature means no repeated Keychain
# prompts. Override with SYNCLIKO_SIGN_IDENTITY=<sha1 or name>. Notarize with mac/notarize.sh.
set -euo pipefail
cd "$(dirname "$0")"
command -v xcodegen >/dev/null || { echo "xcodegen not found: brew install xcodegen"; exit 1; }
xcodegen generate --quiet
CONFIG="${CONFIG:-Debug}"
xcodebuild -project Syncliko.xcodeproj -scheme Syncliko -configuration "$CONFIG" -destination 'platform=macOS' \
  -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO build "$@" \
  | grep -E "error:|warning: |BUILD" | grep -v SourcePackages || true
APP="build/DerivedData/Build/Products/$CONFIG/Syncliko.app"
[[ -d "$APP" ]] || { echo "build failed"; exit 1; }
# Release builds are signed with "Developer ID Application" (what notarization and Gatekeeper need), with a
# secure timestamp; Debug builds with "Apple Development". Override with SYNCLIKO_SIGN_IDENTITY.
KIND=$([[ "$CONFIG" == Release ]] && echo "Developer ID Application" || echo "Apple Development")
IDENTITY="${SYNCLIKO_SIGN_IDENTITY:-$(security find-identity -v -p codesigning | awk -v kind="$KIND" 'index($0, kind) {print $2; exit}')}"
TIMESTAMP=$([[ "$CONFIG" == Release ]] && echo "--timestamp" || echo "--timestamp=none")
rm -rf build/Syncliko.app
cp -R "$APP" build/Syncliko.app
codesign --force --deep --options runtime "$TIMESTAMP" --sign "${IDENTITY:--}" build/Syncliko.app
codesign --verify --strict build/Syncliko.app
echo "→ $(pwd)/build/Syncliko.app (signed with ${IDENTITY:-ad-hoc}, $KIND)"
