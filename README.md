# PIVOT

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/ApoSkunz/PIVOT/badge)](https://securityscorecards.dev/viewer/?uri=github.com/ApoSkunz/PIVOT)

Suite collaborative open-source — outils activables par les administrateurs, auto-hébergeable, sans lock-in SaaS.

## CI/CD

[![CI](https://github.com/ApoSkunz/PIVOT/actions/workflows/ci.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/ci.yml)
[![Release](https://github.com/ApoSkunz/PIVOT/actions/workflows/release.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/release.yml)
[![Mutation Testing](https://github.com/ApoSkunz/PIVOT/actions/workflows/mutation.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/mutation.yml)
[![Lighthouse](https://github.com/ApoSkunz/PIVOT/actions/workflows/lighthouse.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/lighthouse.yml)
[![SBOM](https://github.com/ApoSkunz/PIVOT/actions/workflows/sbom.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/sbom.yml)

## Sécurité

**Scanning actif** (secrets, SAST, supply chain, conformité CI/CD) — `security.yml` : Gitleaks · CodeQL · Semgrep OWASP · Plumber (score CI/CD)

[![Security](https://github.com/ApoSkunz/PIVOT/actions/workflows/security.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/security.yml)
[![OpenSSF Scorecard](https://github.com/ApoSkunz/PIVOT/actions/workflows/scorecard.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/scorecard.yml)
[![DAST Baseline](https://github.com/ApoSkunz/PIVOT/actions/workflows/dast-baseline.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/dast-baseline.yml)
[![DAST Full](https://github.com/ApoSkunz/PIVOT/actions/workflows/dast-full.yml/badge.svg)](https://github.com/ApoSkunz/PIVOT/actions/workflows/dast-full.yml)

**Analyse statique** — SonarCloud : Security Rating · Vulnérabilités · Quality Gate

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ApoSkunz_PIVOT&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ApoSkunz_PIVOT)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=ApoSkunz_PIVOT&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=ApoSkunz_PIVOT)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=ApoSkunz_PIVOT&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=ApoSkunz_PIVOT)

## Qualité

[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=ApoSkunz_PIVOT&metric=bugs)](https://sonarcloud.io/summary/new_code?id=ApoSkunz_PIVOT)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=ApoSkunz_PIVOT&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=ApoSkunz_PIVOT)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=ApoSkunz_PIVOT&metric=coverage)](https://sonarcloud.io/summary/new_code?id=ApoSkunz_PIVOT)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=ApoSkunz_PIVOT&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=ApoSkunz_PIVOT)

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
