# CI/CD — Workflows PIVOT

## Vue d'ensemble

```
PR ouverte / mise à jour
├── ci.yml          ← 12 checks requis, bloquants pour le merge
├── security.yml    ← Gitleaks + CodeQL + Semgrep + Plumber
└── scorecard.yml   ← OpenSSF Scorecard

Merge sur main
├── ci.yml          ← idem PR
├── security.yml    ← idem PR
├── scorecard.yml   ← idem PR
├── release.yml     ← Semantic Release + Docker + Trivy + SLSA
├── dast-full.yml   ← [DESACTIVE] ZAP Full Scan — réactiver quand backend déployé
├── lighthouse.yml  ← [DESACTIVE] Perf + Accessibilité — réactiver quand frontend déployé
└── mutation.yml    ← PIT Mutation Testing

Release publiée (tag)
└── sbom.yml        ← SBOM CycloneDX (Maven + npm) + diff

Planifié
├── security.yml    ← Lundi 06h00 UTC
├── scorecard.yml   ← Lundi 06h00 UTC
└── dast-baseline.yml ← 1er du mois 03h00 UTC (scan prod)

Manuel (workflow_dispatch)
├── dast-full.yml
├── dast-baseline.yml
└── sbom.yml
```

---

## Workflows par déclencheur

### Pull Request

Tout PR vers `main` doit franchir **12 checks requis** (branch protection `enforce_admins: true`).

| Check | Workflow | Bloquant |
|-------|----------|----------|
| Code Quality — Java | `ci.yml` | oui |
| Code Quality — Angular | `ci.yml` | oui |
| Tests Backend (TU + TI) | `ci.yml` | oui |
| Tests Frontend (Vitest) | `ci.yml` | oui |
| E2E — Playwright | `ci.yml` | oui |
| SonarCloud Analysis | `ci.yml` | oui |
| SCA — Dependency Audit | `ci.yml` | oui |
| CodeQL — SAST (java) | `security.yml` | oui |
| CodeQL — SAST (javascript) | `security.yml` | oui |
| Semgrep — SAST | `security.yml` | oui |
| Plumber — CI/CD Compliance | `security.yml` | oui |
| Gitleaks — Secret Scan | `security.yml` | oui |

---

### Push sur `main`

Déclenche tous les workflows PR **+** les workflows post-merge ci-dessous.

---

## Détail des workflows

### `ci.yml` — Intégration continue

**Déclencheurs :** PR · push `main`
**Concurrence :** annule le run précédent du même workflow sur la même branche.

| Job | Ce qu'il fait |
|-----|--------------|
| **Code Quality — Java** | Checkstyle + SpotBugs + compilation |
| **Code Quality — Angular** | TypeScript strict + ESLint |
| **Tests Backend (TU + TI)** | JUnit 5 + Testcontainers (PostgreSQL 18 + Redis 7) · rapport JaCoCo |
| **Tests Frontend (Vitest)** | Vitest + coverage · rapport `lcov.info` |
| **E2E — Playwright** | Build backend + Angular · Chromium · tests `frontend/e2e/` |
| **SonarCloud Analysis** | Analyse qualité SonarCloud (coverage + duplication + hotspots) |
| **SCA — Dependency Audit** | Trivy `fs` scan (backend + frontend) · SARIF → GitHub Security · bloque sur CVE CRITICAL/HIGH |

Artifacts conservés : `backend-coverage` · `frontend-coverage` · `e2e-report` (30 jours).

**SonarCloud** : propriétés passées via `-D` (organisation, projectKey, binaires, sources, exclusions). Pas de `sonar-project.properties` auto-découvert — fichier de référence dans `.github/workflows/configuration/sonar-project.properties`.

---

### `security.yml` — Sécurité applicative

**Déclencheurs :** PR · push `main` · **schedule lundi 06h00 UTC**

| Job | Outil | Ce qu'il fait |
|-----|-------|--------------|
| **Gitleaks — Secret Scan** | Gitleaks v2 (licence pro) | Scan historique git complet (`fetch-depth: 0`). Bloc sur tout secret détecté. SARIF → GitHub Security. |
| **CodeQL — SAST** | CodeQL v4 | Analyse statique Java + JavaScript. Matrix 2 jobs parallèles. SARIF → GitHub Security. Exclusion CSRF (`java/spring-disabled-csrf-protection`) — API stateless JWT, pas de session cookie. |
| **Semgrep — SAST** | Semgrep v1 | Règles : `java`, `spring`, `owasp-top-ten`, `security-audit`, `jwt`, `sql-injection`, `xss`, `command-injection`, `secrets`, `javascript`, `typescript`. |
| **Plumber — CI/CD Compliance** | Plumber | Vérifie conformité pipelines CI. SARIF + rapport texte. |

---

### `release.yml` — Release automatique

**Déclencheurs :** push `main` (hors Dependabot)
**Permissions élevées :** `contents: write` · `packages: write` · `attestations: write`

Séquence d'exécution :

```
1. Build backend (Maven package)
2. Build frontend (Angular production)
3. Push images Docker → ghcr.io
   - pivot-backend:{sha} + :latest
   - pivot-frontend:{sha} + :latest
4. Semantic Release → tag Git + CHANGELOG + GitHub Release
   feat: → minor bump | fix: → patch | feat!: → major
5. Trivy scan image backend (CRITICAL + HIGH → SARIF)
6. Génération checksums SLSA (jar + js/css)
7. Attestation SLSA L2 (actions/attest-build-provenance)
```

**Secret requis :** `SEMANTIC_RELEASE_TOKEN` (PAT avec droits `contents` + `issues` + `pull-requests`).

---

### `dast-full.yml` — DAST Full Scan

**Déclencheurs :** `workflow_dispatch` uniquement (**désactivé** — réactiver quand backend déployé)
**Non bloquant** (`continue-on-error: true`) — résultats en issues GitHub.

Démarre une instance backend locale avec profil `dast-seed` (données mock, jamais prod).
Lance **2 jobs parallèles** :

| Job | Rôle testé | Endpoint vérifié |
|-----|-----------|-----------------|
| ZAP Full Scan — ROLE_USER | `dast-user@pivot.local` | `GET /api/users/me` → 200 |
| ZAP Full Scan — ROLE_ADMIN | `dast-admin@pivot.local` | `GET /api/admin/users` → 200 |

Chaque job :
- Authentifie via `POST /api/auth/login` → récupère JWT
- Injecte `Authorization: Bearer <token>` dans les headers ZAP
- Scan ZAP complet sur `http://<host>:8080/api`
- Crée une issue GitHub si alertes détectées
- Upload rapport HTML + JSON (30 jours)

---

### `dast-baseline.yml` — DAST Baseline Prod

**Déclencheurs :** **schedule 1er du mois 03h00 UTC** · `workflow_dispatch`

Scan ZAP passif sur `${{ secrets.PIVOT_PROD_URL }}` (URL de production réelle).
Non bloquant. Crée une issue GitHub `[DAST] ZAP Baseline — alertes de sécurité PIVOT` si alertes.

---

### `lighthouse.yml` — Performance & Accessibilité

**Déclencheurs :** `workflow_dispatch` uniquement (**désactivé** — réactiver quand frontend déployé)
**Non bloquant** (`continue-on-error: true`)

Démarre backend + sert le build Angular en production.
Lighthouse CI mesure sur les pages définies dans `.lighthouserc.js` :

| Métrique | Description |
|----------|-------------|
| Performance | Score Lighthouse (0–100) |
| Accessibility | WCAG compliance (cible ≥ 90) |
| Best Practices | Sécurité headers, HTTPS, etc. |
| SEO | Balises meta, robots |

Résumé affiché dans le **Job Summary** GitHub. Rapport HTML uploadé (7 jours).

---

### `mutation.yml` — Mutation Testing

**Déclencheurs :** push `main`
**Non bloquant** (`continue-on-error: true`)

Exécute [PIT Mutation Testing](https://pitest.org/) sur le backend Java.
Seuils configurés : `mutationThreshold=60` · `coverageThreshold=60`.
Rapport HTML uploadé (14 jours).

> Indicateur de qualité des tests : si PIT tue moins de 60 % des mutants, les tests passent sans détecter des bugs réels.

---

### `sbom.yml` — Software Bill of Materials

**Déclencheurs :** **release publiée** · `workflow_dispatch`

Génère deux SBOMs au format CycloneDX JSON :

| SBOM | Outil | Contenu |
|------|-------|---------|
| `sbom-backend.cdx.json` | Maven CycloneDX Plugin | Toutes dépendances Java (runtime + test) |
| `sbom-frontend.cdx.json` | `npm sbom` | Dépendances npm runtime uniquement |

Diff automatique vs release précédente (composants ajoutés / supprimés / mis à jour) affiché dans le Job Summary.
Fichiers attachés à la GitHub Release + artifact CI (365 jours).

---

### `scorecard.yml` — OpenSSF Scorecard

**Déclencheurs :** push `main` · **schedule lundi 06h00 UTC** · PR

Évalue la posture de sécurité du dépôt selon les critères [OpenSSF Scorecard](https://github.com/ossf/scorecard) :
branch protection, dependency pinning, CI scores, code review, secret scanning, etc.
Résultat publié dans l'onglet Security → SARIF.

---

## Secrets requis

| Secret | Scope | Usage |
|--------|-------|-------|
| `SEMANTIC_RELEASE_TOKEN` | Repo | Semantic Release — push tags + release notes |
| `GITLEAKS_LICENCE_KEY` | Org | Gitleaks Pro — rapports SARIF avancés |
| `SONAR_TOKEN` | Repo | SonarCloud — analyse qualité |
| `PIVOT_PROD_URL` | Repo | DAST Baseline — URL production |
| `GITHUB_TOKEN` | Auto | Fourni par GitHub Actions |

---

## Supply chain — Actions pinned

Toutes les GitHub Actions sont épinglées à leur **commit SHA exact** (pas de tag mutable).
Format : `uses: owner/action@<sha40> # vX.Y`

Référence des SHAs dans [`.github/workflows/`](.github/workflows/) — chaque fichier contient les commentaires de version.

---

## Historique des révisions

| Version | Date | Évolutions principales |
|---------|------|------------------------|
| v1 | 2026-06-20 | Documentation initiale — 10 workflows |
| v2 | 2026-06-20 | SCA Trivy (remplace OWASP DC) · CodeQL v4 · SonarCloud props via -D · DAST + Lighthouse désactivés (pas de backend déployé) |
