#!/usr/bin/env bash
#
# dev-versions.sh — détecteur de dérive « image déployée vs branche main ».
#
# Pour chaque service du stack local PIVOT, compare la date de build de l'image du conteneur
# en cours d'exécution à la date du dernier commit de `origin/main` du repo source. Une image
# plus ancienne que main = DÉRIVE (le conteneur ne reflète pas le code courant) — c'est la cause
# des « versions incohérentes avec main » (une sticky qui ne s'affiche pas, un déplacement qui
# retombe, etc. : front/back désynchronisés).
#
# Le frontend dépend de PLUSIEURS sources (pivot-ui + les libs *-ui/design-system embarquées) :
# on prend la plus récente des têtes main concernées.
#
# Sortie : tableau + code retour non-zéro si au moins une dérive est détectée (utilisable en garde
# avant une session de test, ou en CI locale).
#
# Usage : pivot-core/dev-versions.sh
#         ROOT=/chemin/workspace pivot-core/dev-versions.sh    # override racine workspace
set -uo pipefail

ROOT="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# service_container | repos_sources (séparés par des virgules, relatifs à ROOT)
MAP=(
  "pivot-backend|pivot-core"
  "pivot-collaboratif-core|pivot-collaboratif-core"
  "pivot-pilotage-core|pivot-pilotage-core"
  "pivot-agilite-core|pivot-agilite-core"
  "pivot-frontend|pivot-ui,pivot-design-system,pivot-collaboratif-ui,pivot-pilotage-ui,pivot-agilite-ui"
)

epoch_of_image() { # $1 = container
  local created
  created=$(docker inspect "$1" --format '{{.Created}}' 2>/dev/null) || return 1
  date -d "$created" +%s 2>/dev/null
}

epoch_of_main() { # $1 = repo dir → epoch du dernier commit origin/main (fetch silencieux)
  local dir="$ROOT/$1"
  [ -d "$dir/.git" ] || { echo 0; return; }
  git -C "$dir" fetch -q origin main 2>/dev/null || git -C "$dir" fetch -q origin master 2>/dev/null || true
  local ref
  for ref in origin/main origin/master HEAD; do
    if git -C "$dir" rev-parse --verify -q "$ref" >/dev/null 2>&1; then
      git -C "$dir" log -1 --format=%ct "$ref" 2>/dev/null && return
    fi
  done
  echo 0
}

printf "%-26s %-20s %-20s %s\n" "SERVICE" "IMAGE BUILD (UTC)" "MAIN HEAD (UTC)" "ÉTAT"
printf '%s\n' "--------------------------------------------------------------------------------------------"

drift=0
for row in "${MAP[@]}"; do
  container="${row%%|*}"
  repos="${row#*|}"

  if ! img_epoch=$(epoch_of_image "$container"); then
    printf "%-26s %-20s %-20s %s\n" "$container" "—" "—" "⚠ conteneur absent"
    drift=1; continue
  fi

  newest_main=0; newest_repo=""
  IFS=',' read -ra rlist <<< "$repos"
  for r in "${rlist[@]}"; do
    e=$(epoch_of_main "$r")
    if [ "$e" -gt "$newest_main" ]; then newest_main=$e; newest_repo=$r; fi
  done

  img_h=$(date -u -d "@$img_epoch" '+%Y-%m-%d %H:%M' 2>/dev/null)
  main_h=$(date -u -d "@$newest_main" '+%Y-%m-%d %H:%M' 2>/dev/null)

  if [ "$newest_main" -eq 0 ]; then
    state="⚠ source main introuvable (repo cloné ? git fetch ?)"; drift=1
  elif [ "$img_epoch" -ge "$newest_main" ]; then
    state="✅ à jour"
  else
    lag=$(( (newest_main - img_epoch) / 3600 ))
    state="🔴 DÉRIVE (~${lag}h de retard sur ${newest_repo})"; drift=1
  fi
  printf "%-26s %-20s %-20s %s\n" "$container" "$img_h" "$main_h" "$state"
done

echo
if [ "$drift" -ne 0 ]; then
  echo "🔴 Dérive détectée — un ou plusieurs conteneurs ne reflètent pas main."
  echo "   → corriger : pivot-core/dev-refresh.sh   (rebuild de tout depuis les sources locales)"
  exit 1
fi
echo "✅ Tous les conteneurs sont alignés sur main."
