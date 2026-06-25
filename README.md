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

## Déploiement (Docker Compose)

```bash
# Cloner et configurer
git clone https://github.com/ApoSkunz/PIVOT.git
cd PIVOT
cp .env.example .env
# Éditer .env — DATABASE_URL, REDIS_URL, OIDC_ISSUER, JWT_SECRET

# Démarrer
docker compose up -d
```

Services :
- Frontend : http://localhost:4200
- API : http://localhost:8080
- Healthcheck : http://localhost:8080/actuator/health
- API Docs (OpenAPI) : http://localhost:8080/swagger-ui.html

## Prérequis (développement local)

| Outil | Version |
|-------|---------|
| Java | 21+ |
| Maven | 3.9+ |
| Node.js | 20+ |
| Angular CLI | 18+ |
| Docker | 24+ |

## Démarrage local

```bash
# PostgreSQL + Redis
docker compose up -d postgres redis

# Backend
cd backend
cp src/main/resources/application-local.properties.example \
   src/main/resources/application-local.properties
mvn spring-boot:run -Dspring-boot.run.profiles=local

# Frontend (autre terminal)
cd frontend
npm install
ng serve
```

## SSO OIDC local

```bash
docker compose --profile sso up -d keycloak
```

Configurer `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` dans `application-local.properties`.

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
| Décisions d'architecture | [`docs/adr/`](docs/adr/) |
| Audits | [`docs/audits/`](docs/audits/) |

## Licence

[GNU Affero General Public License v3.0](LICENSE) — les modifications déployées comme service réseau doivent être publiées.
