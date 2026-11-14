#!/bin/zsh
# Renders every raster icon from mark.svg with headless Chrome, then scales with sips.
#   macOS app icon → mac/SynclikoMac/Resources/Assets.xcassets/AppIcon.appiconset (Apple grid: 824 body in 1024)
#   site           → site/icon.png (1024), icon-512/64/32 (rounded tile), icon-180 (square, iOS rounds it)
#   DMG background → mac/dmg/background.png and @2x, from dmg-background.svg
# Android uses vector drawables (res/drawable/ic_launcher_*.xml, ic_tile.xml) and the Mac menu bar glyph is
# drawn in code (MenuBarIcon in SynclikoApp.swift); both reuse the coordinates in mark.svg.
set -e
cd "${0:A:h}"
ROOT=..
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
TMP=$(mktemp -d)
MARK=$(sed -n '/<path/,/<\/svg>/p' mark.svg | sed '$d')
# Cobalt tile with a soft top-to-bottom shade, as on the Mac.
FILL="<linearGradient id='g' x1='0' y1='0' x2='0' y2='1'><stop offset='0' stop-color='#3A5CFF'/><stop offset='1' stop-color='#1F3BDB'/></linearGradient>"

page() {  # $1 = file, $2 = svg body (1024 viewBox)
  cat > "$1" <<HTML
<!doctype html><html><head><style>html,body{margin:0;background:transparent}svg{display:block}</style></head>
<body><svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">$2</svg></body></html>
HTML
}
shot() {  # $1 = html, $2 = png
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --default-background-color=00000000 \
    --force-device-scale-factor=1 --window-size=1024,1024 --screenshot="$2" "file://$1" >/dev/null 2>&1
}

# macOS: squircle body on Apple's 1024 grid with the standard soft shadow; the S is 57% of the body.
page "$TMP/mac.html" "<defs>$FILL<filter id='s' x='-10%' y='-10%' width='120%' height='130%'><feDropShadow dx='0' dy='10' stdDeviation='12' flood-color='#000' flood-opacity='0.3'/></filter></defs>
<rect x='100' y='100' width='824' height='824' rx='185' fill='url(#g)' filter='url(#s)'/>
<g transform='translate(512 512) scale(6.8) translate(-50 -50)'>$MARK</g>"
shot "$TMP/mac.html" "$TMP/mac.png"
# Site tile (favicon, links): full-bleed rounded square, a slightly bigger S for small sizes.
page "$TMP/tile.html" "<defs>$FILL</defs><rect width='1024' height='1024' rx='230' fill='url(#g)'/>
<g transform='translate(512 512) scale(7.6) translate(-50 -50)'>$MARK</g>"
shot "$TMP/tile.html" "$TMP/tile.png"
# iOS touch icon: full-bleed square, iOS rounds it.
page "$TMP/square.html" "<defs>$FILL</defs><rect width='1024' height='1024' fill='url(#g)'/>
<g transform='translate(512 512) scale(7) translate(-50 -50)'>$MARK</g>"
shot "$TMP/square.html" "$TMP/square.png"

SET=$ROOT/mac/SynclikoMac/Resources/Assets.xcassets/AppIcon.appiconset
for s in 16 32 128 256 512; do
  sips -z $s $s "$TMP/mac.png" --out "$SET/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) "$TMP/mac.png" --out "$SET/icon_${s}x${s}@2x.png" >/dev/null
done
cp "$TMP/tile.png" $ROOT/site/icon.png
for s in 512 64 32; do sips -z $s $s "$TMP/tile.png" --out $ROOT/site/icon-$s.png >/dev/null; done
sips -z 180 180 "$TMP/square.png" --out $ROOT/site/icon-180.png >/dev/null
# DMG window background (mac/dmg), 1x and 2x.
for scale in 1 2; do
  cat > "$TMP/dmg.html" <<HTML
<!doctype html><html><head><style>html,body{margin:0}svg{display:block}</style></head><body>$(cat dmg-background.svg)</body></html>
HTML
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=$scale --window-size=640,440 \
    --screenshot="$TMP/dmg$scale.png" "file://$TMP/dmg.html" >/dev/null 2>&1
done
cp "$TMP/dmg1.png" $ROOT/mac/dmg/background.png
cp "$TMP/dmg2.png" $ROOT/mac/dmg/background@2x.png
rm -rf "$TMP"
echo "icons rendered"
