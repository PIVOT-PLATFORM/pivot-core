#!/usr/bin/env bash
#
# dev-refresh.sh — GARANTIT que le stack local reflète `main` (élimine la désync image/branche).
#
# À exécuter dès qu'on a `git pull` main quelque part, ou dès que `dev-versions.sh` signale une
# dérive. Contrairement à `dev-up.sh` (qui build le frontend depuis les packages PUBLIÉS sur
# GitHub Packages — donc en retard sur main tant qu'aucune release n'a été publiée), `dev-refresh.sh`
# build le frontend depuis les SOURCES LOCALES des libs UI (pack-local-ui.sh + compose.local.yml).
# C'est la seule façon fiable d'avoir front ET back sur exactement le même code que main.
#
# Étapes :
#   1. (option --pull) git pull --ff-only sur tous les repos siblings
#   2. pack des libs UI depuis les sources locales (design-system + collaboratif/agilite)
#   3. (option --reset-db) reset des schémas modules — évite le piège Flyway « V1 édité en place »
#      (checksum mismatch quand une image plus récente re-migre une base déjà migrée). DESTRUCTIF :
#      supprime les données de test des schémas collaboratif/agilite.
#   4. rebuild + recreate de TOUS les services depuis les sources locales
#   5. dev-versions.sh — confirme zéro dérive
#
# Usage :
#   ./dev-refresh.sh                 # rebuild tout depuis main local
#   ./dev-refresh.sh --pull          # git pull main d'abord
#   ./dev-refresh.sh --reset-db      # + reset schémas modules (si checksum Flyway bloque le boot)
#   ./dev-refresh.sh --pull --reset-db
set -euo pipefail
cd "$(dirname "$0")"

DO_PULL=0; DO_RESET_DB=0
for a in "$@"; do
  case "$a" in
    --pull) DO_PULL=1 ;;
    --reset-db) DO_RESET_DB=1 ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Option inconnue : $a" >&2; exit 1 ;;
  esac
done

WORKSPACE="$(cd .. && pwd)"
SIBLINGS=(pivot-core pivot-ui pivot-design-system pivot-collaboratif-core pivot-collaboratif-ui \
          pivot-agilite-core pivot-agilite-ui)

command -v docker >/dev/null || { echo "❌ docker introuvable"; exit 1; }

# Credentials de build (mêmes que dev-up.sh) : GitHub Packages Maven + npm.
if [[ -z "${GITHUB_ACTOR:-}" || -z "${GITHUB_TOKEN:-}" ]]; then
  command -v gh >/dev/null && gh auth status >/dev/null 2>&1 || { echo "❌ exporte GITHUB_ACTOR/GITHUB_TOKEN ou 'gh auth login'"; exit 1; }
  export GITHUB_ACTOR="${GITHUB_ACTOR:-$(gh api user -q .login)}"
  export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
fi
export NODE_AUTH_TOKEN="${NODE_AUTH_TOKEN:-$GITHUB_TOKEN}"

if (( DO_PULL )); then
  echo "▶ git pull --ff-only sur les repos siblings…"
  for r in "${SIBLINGS[@]}"; do
    [ -d "$WORKSPACE/$r/.git" ] || continue
    echo "  · $r"; git -C "$WORKSPACE/$r" pull --ff-only 2>&1 | sed 's/^/    /' || echo "    ⚠ pull ignoré ($r)"
  done
fi

echo "▶ pack des libs UI depuis les sources locales…"
bash scripts/pack-local-ui.sh

if (( DO_RESET_DB )); then
  echo "▶ reset des schémas modules (piège Flyway V1)…"
  if docker ps --format '{{.Names}}' | grep -qx pivot-postgres; then
    for schema in collaboratif agilite; do
      docker exec pivot-postgres psql -U pivot -d pivot_dev \
        -c "DROP SCHEMA IF EXISTS $schema CASCADE; CREATE SCHEMA $schema AUTHORIZATION pivot;" \
        >/dev/null 2>&1 && echo "  · schéma $schema réinitialisé" || echo "  ⚠ reset $schema échoué"
    done
  else
    echo "  ⚠ conteneur pivot-postgres absent — reset ignoré (il sera créé propre au up)"
  fi
fi

echo "▶ rebuild + recreate de tous les services depuis les sources locales…"
docker compose -f compose.yml -f compose.local.yml up -d --build --force-recreate

echo
echo "▶ vérification de la dérive…"
ROOT="$WORKSPACE" bash "$(dirname "$0")/dev-versions.sh" || {
  echo "⚠ une dérive subsiste — voir ci-dessus (un rebuild d'un service a peut-être échoué)."; exit 1;
}
echo "✅ Stack rafraîchie et alignée sur main."
