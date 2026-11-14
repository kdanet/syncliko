#!/bin/zsh
# Notarizes and staples a Developer ID–signed app or disk image:
#   mac/notarize.sh mac/build/Syncliko.app
#   mac/notarize.sh dist/Syncliko-0.3.2.dmg
# Uses the notarytool credentials saved in the Keychain under SYNCLIKO_NOTARY_PROFILE (default
# "syncliko-notary"). Save them once with:
#   xcrun notarytool store-credentials syncliko-notary --apple-id <you@example.com> --team-id <TEAMID>
# (it asks for an app-specific password from account.apple.com).
set -euo pipefail
TARGET="${1:?usage: notarize.sh <Syncliko.app | Syncliko.dmg>}"
PROFILE="${SYNCLIKO_NOTARY_PROFILE:-syncliko-notary}"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if [[ "$TARGET" == *.app ]]; then
  # notarytool takes a zip, a dmg or a pkg; the ticket is stapled to the app itself.
  ditto -c -k --keepParent "$TARGET" "$WORK/upload.zip"
  UPLOAD="$WORK/upload.zip"
else
  UPLOAD="$TARGET"
fi
echo "Submitting $(basename "$TARGET") for notarization…"
if ! xcrun notarytool submit "$UPLOAD" --keychain-profile "$PROFILE" --wait --output-format json > "$WORK/result.json"; then
  cat "$WORK/result.json"; exit 1
fi
STATUS=$(/usr/bin/python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"])' "$WORK/result.json")
if [[ "$STATUS" != "Accepted" ]]; then
  ID=$(/usr/bin/python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$WORK/result.json")
  echo "Notarization $STATUS. Log:"; xcrun notarytool log "$ID" --keychain-profile "$PROFILE"; exit 1
fi
xcrun stapler staple "$TARGET"
xcrun stapler validate "$TARGET"
spctl --assess --type $([[ "$TARGET" == *.app ]] && echo execute || echo open) --context context:primary-signature -v "$TARGET" 2>&1 | head -2
echo "✓ notarized and stapled: $TARGET"
