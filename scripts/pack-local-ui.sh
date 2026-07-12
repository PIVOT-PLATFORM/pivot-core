#!/usr/bin/env bash
# Build the @pivot-platform/*-ui libraries from the local sibling repos and pack them into
# stable-named tarballs under pivot-ui/local-ui-packages/, so the shell can be built entirely
# offline (see pivot-ui/Dockerfile.local + pivot-core/compose.local.yml). Nothing is fetched
# from GitHub Packages.
#
# Usage: pivot-core/scripts/pack-local-ui.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"   # pivot workspace root (parent of all repos)
DEST="$ROOT/pivot-ui/local-ui-packages"

rm -rf "$DEST"
mkdir -p "$DEST"

# pack_lib <sibling-repo> <angular-project> <stable-tarball-name>
pack_lib () {
  local repo="$1" project="$2" stable="$3"
  local dir="$ROOT/$repo"
  if [ ! -d "$dir" ]; then
    echo "⚠ skip $repo (not cloned)"; return 0
  fi
  echo "▶ $repo → building $project"
  ( cd "$dir"
    [ -d node_modules ] || npm ci --no-audit --no-fund
    npx ng build "$project" --configuration production
    local tgz
    tgz="$(npm pack "./dist/$project" --pack-destination "$DEST" --json \
      | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d)[0].filename))")"
    mv "$DEST/$tgz" "$DEST/$stable"
    echo "  ✓ $stable" )
}

pack_lib pivot-collaboratif-ui collaboratif-ui pivot-platform-collaboratif-ui.tgz
pack_lib pivot-pilotage-ui     pilotage-ui     pivot-platform-pilotage-ui.tgz
pack_lib pivot-agilite-ui      agilite-ui      pivot-platform-agilite-ui.tgz

echo "✓ local UI packages ready in $DEST"
ls -1 "$DEST"
