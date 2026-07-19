#!/usr/bin/env bash
#
# dev-up.sh — lance TOUTE la plateforme PIVOT en local (compose.yml de ce repo).
#
# Construit et démarre : backend (pivot-core, modulith incluant les modules agilite +
# collaboratif — EN53/ADR-030) + frontend (pivot-ui) + l'infra (postgres, pgbouncer, redis,
# activemq, mailpit). Seuls ../pivot-ui et ../pivot-core sont nécessaires : les anciens
# module-cores standalone sont retirés du compose (repos archivés).
#
# Ce script résout automatiquement les credentials de BUILD (jamais committés) :
#   - GITHUB_ACTOR / GITHUB_TOKEN : GitHub Packages (Maven) — dépendance privée
#     fr.pivot:pivot-core-starter des module-cores agilite/collaboratif.
#   - NODE_AUTH_TOKEN            : GitHub Packages (npm) — packages privés @pivot-platform/*
#     du frontend.
# Il les prend depuis l'environnement s'ils sont déjà exportés, sinon depuis `gh` (GitHub CLI).
#
# Usage :  ./dev-up.sh            # build (séquentiel) + up -d
#          ./dev-up.sh --pull     # idem, en rafraîchissant d'abord les images de base
#
set -euo pipefail
cd "$(dirname "$0")"

# 1. Prérequis Docker
command -v docker >/dev/null 2>&1 || { echo "❌ docker introuvable"; exit 1; }
docker info >/dev/null 2>&1        || { echo "❌ le daemon Docker n'est pas démarré"; exit 1; }

# 2. Credentials de build — priorité à l'environnement, sinon dérivés de `gh`.
if [[ -z "${GITHUB_ACTOR:-}" || -z "${GITHUB_TOKEN:-}" ]]; then
  command -v gh >/dev/null 2>&1 || {
    echo "❌ GITHUB_ACTOR/GITHUB_TOKEN non exportés et 'gh' introuvable."
    echo "   → soit: export GITHUB_ACTOR=<user> GITHUB_TOKEN=<PAT read:packages>"
    echo "   → soit: installer GitHub CLI et 'gh auth login'"; exit 1; }
  gh auth status >/dev/null 2>&1 || { echo "❌ 'gh' non authentifié — lance: gh auth login"; exit 1; }
  export GITHUB_ACTOR="${GITHUB_ACTOR:-$(gh api user -q .login)}"
  export GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token)}"
fi
# npm (GitHub Packages) réutilise le même token que Maven.
export NODE_AUTH_TOKEN="${NODE_AUTH_TOKEN:-$GITHUB_TOKEN}"

echo "▶ GITHUB_ACTOR=$GITHUB_ACTOR (token ${#GITHUB_TOKEN} chars) — build + up…"

# 3. Build + démarrage. Le build parallèle est SÛR même à froid : les Dockerfiles des services
#    Java verrouillent leur cache Maven partagé (`--mount=type=cache,target=/root/.m2,sharing=locked`),
#    ce qui évite la corruption du zip du Maven Wrapper qu'un build parallèle provoquait avant.
if [[ "${1:-}" == "--pull" ]]; then docker compose pull --ignore-buildable || true; fi
docker compose up -d --build

echo
echo "✅ Stack PIVOT démarrée :"
echo "     UI (SPA + gateway API)  http://localhost/"
echo "     API pivot-core          http://localhost/api/…      (health: http://localhost:8081/actuator/health)"
echo "     Modules                 http://localhost/api/{agilite,collaboratif}/…"
echo "     Mailpit (emails dev)     http://localhost:8025/"
echo "     ActiveMQ console         http://localhost:8161/     (admin/admin)"
echo
docker compose ps
