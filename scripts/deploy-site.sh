#!/bin/zsh
# Publishes site/ to the gh-pages branch, which GitHub Pages serves at https://kdanet.github.io/syncliko/.
# The branch holds only the built site (one commit, force-pushed); the source of truth stays in site/.
set -euo pipefail
ROOT=${0:A:h:h}
REMOTE=$(git -C "$ROOT" remote get-url origin)
SOURCE=$(git -C "$ROOT" rev-parse --short HEAD)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -R "$ROOT/site/." "$WORK/"
touch "$WORK/.nojekyll"   # serve files as they are, no Jekyll processing
cd "$WORK"
git init -q -b gh-pages
git add -A
git -c user.name="$(git -C "$ROOT" config user.name)" -c user.email="$(git -C "$ROOT" config user.email)" \
  commit -q -m "Site from $SOURCE"
# Authenticate with the GitHub CLI, like the main repository (see README → Releasing).
git -c credential.helper= -c "credential.helper=!gh auth git-credential" push -q -f "$REMOTE" gh-pages
print "✓ site deployed from $SOURCE → https://kdanet.github.io/syncliko/"
