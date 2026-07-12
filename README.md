# PIVOT

<div align="center">

[![CI](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/ci.yml/badge.svg)](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/PIVOT-PLATFORM/pivot-core?label=release&color=blue)](https://github.com/PIVOT-PLATFORM/pivot-core/releases)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.x-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Downloads](https://img.shields.io/github/downloads/PIVOT-PLATFORM/pivot-core/total?label=downloads)](https://github.com/PIVOT-PLATFORM/pivot-core/releases)
[![Docker](https://img.shields.io/badge/docker-GHCR-2496ED?logo=docker&logoColor=white)](https://github.com/PIVOT-PLATFORM/pivot-core/pkgs/container/pivot-core%2Fpivot-core)
[![License](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)

[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=coverage)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/PIVOT-PLATFORM/pivot-core/badge)](https://securityscorecards.dev/viewer/?uri=github.com/PIVOT-PLATFORM/pivot-core)
[![SLSA Level 3](https://img.shields.io/badge/SLSA-Level_3-1B6B2F?logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0xMiAxTDMgNXY2YzAgNS41NSAzLjg0IDEwLjc0IDkgMTIgNS4xNi0xLjI2IDktNi40NSA5LTEyVjVsLTktNHoiLz48L3N2Zz4=)](https://slsa.dev)
[![Plumber Score](https://img.shields.io/badge/Plumber-A-brightgreen?logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0xMiAyQzYuNDggMiAyIDYuNDggMiAxMnM0LjQ4IDEwIDEwIDEwIDEwLTQuNDggMTAtMTBTMTcuNTIgMiAxMiAyek0xMCAxN2wtNS01IDEuNDEtMS40MUwxMCAxNC4xN2w3LjU5LTcuNTlMMTkgOGwtOSA5eiIvPjwvc3ZnPg==)](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/security.yml)

</div>

Suite collaborative open-source — outils activables par les administrateurs, auto-hébergeable, sans lock-in SaaS.

## CI/CD

[![CI](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/ci.yml/badge.svg)](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/ci.yml)
[![Release](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/release.yml/badge.svg)](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/release.yml)
[![SBOM](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/sbom.yml/badge.svg)](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/sbom.yml)

## Sécurité

**Scanning actif** (secrets, SAST, supply chain, conformité CI/CD) — `security.yml` : Gitleaks · CodeQL · Semgrep OWASP · Plumber (score CI/CD)

[![Security](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/security.yml/badge.svg)](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/security.yml)
[![OpenSSF Scorecard](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/scorecard.yml/badge.svg)](https://github.com/PIVOT-PLATFORM/pivot-core/actions/workflows/scorecard.yml)

**Analyse statique** — SonarCloud : Security Rating · Vulnérabilités · Quality Gate

[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=coverage)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)
[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=bugs)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=PIVOT-PLATFORM_pivot-core&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=PIVOT-PLATFORM_pivot-core)

## Modules

| Module | Description | Statut |
|--------|-------------|--------|
| `whiteboard` | Tableau blanc collaboratif temps réel | ⬜ À faire |
| `session` | Sessions live (QUIZ, POLL, WORDCLOUD, BRAINSTORM, QA) | ⬜ À faire |
| `roadmap` | Roadmap / Gantt intégré | ⬜ À faire |
| `survey` | Système de sondage | ⬜ À faire |
| `quiz` | Quiz interactif gamifié | ⬜ À faire |

Chaque module est activable individuellement par les administrateurs tenant.

## Stack technique

| Couche | Technologie |
|--------|-------------|
| Backend | Java 25 · Spring Boot 4.x · Maven |
| Frontend | Angular 22 · TypeScript · SCSS |
| BDD | PostgreSQL 18 · Spring Data JPA · Flyway |
| Cache / Temps réel | Redis · Spring WebSocket (STOMP) |
| Auth | Spring Security · JWT · OIDC (Keycloak, Azure AD, Okta…) |
| Tests | JUnit 5 · Mockito · Testcontainers · Jest · Playwright |
| Observabilité | Spring Actuator · Micrometer · Prometheus |

## Développement local — lancer tout l'écosystème

`compose.yml` (ce repo) est l'**orchestrateur** de la plateforme complète en local : il build et
démarre le backend + les trois module-cores + le frontend, plus toute l'infra. PIVOT est un
multi-repo : les repos siblings doivent être **clonés côte à côte** (mêmes parents), car le compose
les build depuis `../` :

```
un-dossier-parent/
├── pivot-core/                 # ← ce repo (backend shell + compose.yml orchestrateur)
├── pivot-ui/                   # frontend Angular (service `frontend`, nginx :80)
├── pivot-pilotage-core/        # module-core Pilotage  (:8081 interne, /api/pilotage)
├── pivot-agilite-core/         # module-core Agilité   (:8082 interne, /api/agilite)
└── pivot-collaboratif-core/    # module-core Collaboratif (:8083 interne, /api/collaboratif)
```

### Prérequis

| Outil | Version | Note |
|-------|---------|------|
| Docker (+ Compose v2) | 24+ | seule dépendance runtime — tout build dans des conteneurs |
| GitHub CLI (`gh`) | — | authentifié (`gh auth login`) ; ou, à défaut, un PAT `read:packages` |

> Java/Maven/Node **ne sont pas requis sur l'hôte** : chaque service build dans son image.

### Credentials de build (GitHub Packages)

Le build tire des packages **privés** de GitHub Packages et exige des credentials **au moment du
build** (secrets BuildKit, jamais dans les layers ni le runtime) :

| Secret compose | Variable d'env source | Sert à |
|----------------|----------------------|--------|
| `github_actor` / `github_token` | `GITHUB_ACTOR` / `GITHUB_TOKEN` | Maven — `fr.pivot:pivot-core-starter` (module-cores agilite & collaboratif) |
| `npm_token` | `NODE_AUTH_TOKEN` | npm — `@pivot-platform/*` (frontend) |

`compose.yml` lit ces variables dans le **shell** qui lance `docker compose` (jamais depuis `.env` —
ce sont des secrets de build, pas des variables du conteneur). Un PAT `read:packages` suffit ;
`gh auth token` fait l'affaire.

### Lancer (une commande)

```bash
cd pivot-core
./dev-up.sh          # résout les tokens via `gh`, puis `docker compose up -d --build`
```

Le **premier build à froid est long** (~15–20 min : chaque service Java télécharge son arbre Maven).
Les builds suivants réutilisent le cache et sont quasi instantanés.

### Lancer (manuel, équivalent)

```bash
cd pivot-core
export GITHUB_ACTOR="$(gh api user -q .login)"
export GITHUB_TOKEN="$(gh auth token)"
export NODE_AUTH_TOKEN="$GITHUB_TOKEN"
docker compose up -d --build
```

> Le build parallèle est **sûr même à froid** : les Dockerfiles des services Java verrouillent leur
> cache Maven partagé (`--mount=type=cache,target=/root/.m2,sharing=locked`), ce qui évite la
> corruption du zip du Maven Wrapper (`zip END header not found`) qu'un `--build` parallèle
> provoquait auparavant. `dev-up.sh` n'est donc qu'un confort (résolution des tokens via `gh`).

### Accès

| Service | URL |
|---------|-----|
| UI (SPA + gateway API nginx) | http://localhost/ |
| API pivot-core (via nginx) | http://localhost/api/… |
| API modules (via nginx) | http://localhost/api/{pilotage,agilite,collaboratif}/… |
| Health backend (port management EN04.2, hors context-path) | http://localhost:8081/actuator/health · groupes `/liveness`, `/readiness` |
| Métriques Prometheus backend | http://localhost:8081/actuator/prometheus |
| Mailpit (emails dev) | http://localhost:8025/ |
| Console ActiveMQ | http://localhost:8161/ |

> Les module-cores ne publient pas de port sur l'hôte : ils passent par le gateway nginx du
> frontend. `pivot-collaboratif-core` expose son Actuator sur un **port de management séparé
> (`9083`, context racine)** — son healthcheck compose le cible directement ; il n'est donc pas
> atteignable via `/api/collaboratif/actuator/health` (404 par conception).

### Config runtime (`.env`, optionnel)

Les valeurs runtime (mots de passe dev, CORS, SMTP, OIDC…) ont des défauts dans `compose.yml`.
Pour les surcharger : `cp .env.example .env` puis éditer. Voir `.env.example` (les credentials de
**build** ci-dessus n'y vont **pas**).

### Production

`docker-compose.prod.yml` (racine du repo) — stack production : `nginx` (image `pivot-ui`,
gateway API + SPA statique), `pivot-core`, `postgres`, `redis`. Réseaux isolés
(`pivot-net-app` / `pivot-net-data`), aucun port backend exposé, secrets via Docker secrets
(pas de `.env` en prod), health checks sur chaque service. Détail complet (mapping des
secrets, procédure de déploiement, limitations connues) :
[pivot-docs — déploiement Docker Compose production](https://pivot-platform.github.io/pivot-docs/deployment/docker-compose-prod).

## Pipeline CI/CD

Un échec bloque les étapes suivantes :

```
push / PR
  ├── 1. Qualité       Checkstyle · SpotBugs · ESLint · TypeScript
  ├── 2. Tests         JUnit + Testcontainers (TU/TI) · Jest Angular
  ├── 3. E2E           Playwright (Chromium)
  ├── 4. SonarCloud    Quality Gate ≥ 80 % coverage code nouveau
  ├── 5. Sécurité      Gitleaks · CodeQL · Semgrep · Plumber
  └── 6. Release       Semantic Release · Docker GHCR · SBOM · SLSA L2

Sur main uniquement :
  ├── Mutation Testing  PIT (seuil 60 %)
  └── Lighthouse        Performance · A11y · Best Practices · SEO
```

## Documentation

| Sujet | Emplacement |
|-------|-------------|
| Instructions Claude Code + agents IA | [`CLAUDE.md`](CLAUDE.md) |
| Contribuer | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| Sécurité & divulgation | [`SECURITY.md`](SECURITY.md) |
| Gestion des secrets (Docker secrets, rotation) | [`docs/deployment/secret-management.md`](docs/deployment/secret-management.md) |
| Décisions d'architecture | [`docs/adr/`](docs/adr/) |
| Audits | [`docs/audits/`](docs/audits/) |

## Licence

[GNU Affero General Public License v3.0](LICENSE) — les modifications déployées comme service réseau doivent être publiées.
