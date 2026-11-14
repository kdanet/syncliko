#!/bin/zsh
# Packages mac/build/Syncliko.app into dist/Syncliko-<version>.dmg: the app and an Applications shortcut
# side by side over a "drag to install" background (mac/dmg). Build the app first:
#   CONFIG=Release mac/build.sh && mac/package.sh
# Needs dmgbuild (pipx install dmgbuild), which lays out the window without scripting Finder.
set -euo pipefail
cd "$(dirname "$0")"
DMGBUILD="${DMGBUILD:-$(command -v dmgbuild || true)}"
[[ -n "$DMGBUILD" ]] || { echo "dmgbuild not found: pipx install dmgbuild"; exit 1; }
APP=build/Syncliko.app
[[ -d "$APP" ]] || { echo "$APP not found: run CONFIG=Release mac/build.sh first"; exit 1; }
VERSION=$(defaults read "$PWD/$APP/Contents/Info" CFBundleShortVersionString)
mkdir -p ../dist
OUT="$(cd ../dist && pwd)/Syncliko-$VERSION.dmg"
rm -f "$OUT"
"$DMGBUILD" -s dmg/settings.py -D app="$APP" -D background=dmg/background.png -D settings_dir=dmg/settings.py \
  "Syncliko" "$OUT" >/dev/null 2> >(grep -v "is deprecated" >&2)
# Sign the image with the same Developer ID as the app (notarize.sh then notarizes and staples it).
IDENTITY="${SYNCLIKO_SIGN_IDENTITY:-$(security find-identity -v -p codesigning | awk '/Developer ID Application/ {print $2; exit}')}"
[[ -n "$IDENTITY" ]] && codesign --force --sign "$IDENTITY" --timestamp "$OUT"
echo "→ $OUT ($(du -h "$OUT" | cut -f1 | tr -d ' '))"
