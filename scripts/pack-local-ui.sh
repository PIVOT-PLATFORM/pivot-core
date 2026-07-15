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

# pack_lib <sibling-repo> <angular-project> <stable-tarball-name> [build-mode] [dist-subdir]
#   build-mode : "ng-build" (défaut, cible architect `ng build <project>`, dist = dist/<project>)
#              | "ng-packagr" (script `npm run build` via ng-packagr, ex. design-system dont le
#                 projet lib n'a PAS de cible architect `build` — dist = dist/ par ng-package.json)
#   dist-subdir : sous-dossier de dist/ à packer (défaut = <angular-project>, ou "." pour dist/)
pack_lib () {
  local repo="$1" project="$2" stable="$3" mode="${4:-ng-build}" dist_subdir="${5:-$2}"
  local dir="$ROOT/$repo"
  if [ ! -d "$dir" ]; then
    echo "⚠ skip $repo (not cloned)"; return 0
  fi
  echo "▶ $repo → building $project ($mode)"
  ( cd "$dir"
    [ -d node_modules ] || npm ci --no-audit --no-fund
    if [ "$mode" = "ng-packagr" ]; then
      npm run build
    else
      npx ng build "$project" --configuration production
    fi
    local tgz
    tgz="$(npm pack "./dist/$dist_subdir" --pack-destination "$DEST" --json \
      | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d)[0].filename))")"
    mv "$DEST/$tgz" "$DEST/$stable"
    echo "  ✓ $stable" )
}

# design-system : lib packagée par ng-packagr (dist/), pas de cible architect `ng build`.
pack_lib pivot-design-system   design-system   pivot-platform-design-system.tgz   ng-packagr .
pack_lib pivot-collaboratif-ui collaboratif-ui pivot-platform-collaboratif-ui.tgz
pack_lib pivot-pilotage-ui     pilotage-ui     pivot-platform-pilotage-ui.tgz
pack_lib pivot-agilite-ui      agilite-ui      pivot-platform-agilite-ui.tgz

echo "✓ local UI packages ready in $DEST"
ls -1 "$DEST"
