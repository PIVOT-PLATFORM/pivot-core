# Audit CI/CD & DevSecOps

## Périmètre

Workflows GitHub Actions, sécurité pipeline, conformité Plumber, secrets, SBOM, SLSA.

## Critères évalués

| Critère | Statut | Notes |
|---------|--------|-------|
| `permissions: read-all` au niveau workflow | ✅ OK | Tous les workflows |
| Permissions minimales par job | ✅ OK | — |
| `persist-credentials: false` sur checkouts | ✅ OK | Sauf `release.yml` (Semantic Release) |
| `timeout-minutes` sur chaque job | ✅ OK | — |
| Gitleaks secret scan | ✅ OK | `security.yml` |
| CodeQL SAST (Java + JS) | ✅ OK | `security.yml` |
| Semgrep SAST (OWASP + Spring + JWT) | ✅ OK | `security.yml` |
| SCA OWASP Dependency Check | ✅ OK | `ci.yml` |
| Plumber CI/CD compliance | ✅ OK | `security.yml` |
| OpenSSF Scorecard | ✅ OK | `scorecard.yml` |
| SonarCloud Quality Gate | ✅ OK | `ci.yml` |
| Semantic Release | ✅ OK | `release.yml` — convention commits |
| Docker build + push GHCR | ✅ OK | `release.yml` |
| Trivy container scan (CRITICAL/HIGH) | ✅ OK | `release.yml` |
| SLSA L2 provenance | ✅ OK | `release.yml` — `attest-build-provenance` |
| DAST ZAP Full + Baseline | ✅ OK | `dast-full.yml` + `dast-baseline.yml` |
| Dependabot (Maven + npm + Actions) | ✅ OK | `.github/dependabot.yml` |
| Configs centralisées dans `configuration/` | ✅ OK | Plumber, Sonar, Gitleaks, ZAP rules |
| SBOM généré | ✅ OK | `sbom.yml` |
| Mutation testing (PIT) | ✅ OK | `mutation.yml` (seuil PIT) |
| Lighthouse CI | ✅ OK | `lighthouse.yml` |

## Scores OpenSSF Scorecard (cibles)

| Check | Cible |
|-------|-------|
| Branch-Protection | ≥ 8/10 |
| Token-Permissions | 10/10 |
| Dangerous-Workflow | 10/10 |
| Dependency-Update-Tool | 10/10 (Dependabot ✅) |
| SAST | 10/10 |
| Vulnerabilities | 10/10 |

## Historique des révisions

| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | 2026-06-20 | 8/10 | Audit initial — pipeline complet (CI, sécurité, release, SBOM, mutation, Lighthouse, DAST, Scorecard) ; manque profils Maven coverage/PIT dans pom.xml |
