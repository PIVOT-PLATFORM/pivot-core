# CLAUDE.md — PIVOT

## Projet

**PIVOT** — suite collaborative open-source, ensemble d'outils activables par les administrateurs.

**Vision :** rendre accessible à tous (associations, TPE/PME, entreprises) des outils collaboratifs de qualité, auto-hébergeables, sans lock-in SaaS.

**Modules prévus (activables individuellement par les admins) :**

| Module | Description | Inspiration |
|--------|-------------|-------------|
| `whiteboard` | Tableau blanc collaboratif temps réel | PouetPouet |
| `session` | Sessions live : QUIZ, POLL, WORDCLOUD, BRAINSTORM, QA | Klaxoon |
| `roadmap` | Roadmap / Gantt intégré | - |
| `survey` | Système de sondage | - |
| `quiz` | Quiz interactif gamifié | Kahoot |

**Déploiement :**
- Web internet public (SaaS auto-hébergeable)
- Livraison enterprise : connecteur OIDC + définition des rôles tenant

**Base de reprise :** POC PouetPouet (Node.js/TypeScript) — refonte complète en Java/Angular.

---

## Communication

Concise et directe. Techniquement précise. Pas de récapitulatifs inutiles.

**Exceptions (réponses complètes et structurées) :**
- Rédaction ou revue d'US / Epics
- Décisions d'architecture (module system, schéma BDD, OIDC)
- Avis cybersécurité ou actions irréversibles — **confirmation obligatoire**
- Backlog et critères d'acceptation

---

## Stack technique

| Couche | Technologie |
|--------|-------------|
| Backend | Java 25 · Spring Boot 4.x · Maven |
| Frontend | Angular 22 · TypeScript · SCSS |
| BDD | PostgreSQL 18 · Spring Data JPA · Flyway |
| Cache / Temps réel | Redis · Spring WebSocket (STOMP) |
| Auth | Spring Security · JWT · OIDC (compatible tout IdP : Keycloak, Azure AD, Okta…) |
| Tests | JUnit 5 · Mockito · Testcontainers (TI) · Vitest (Angular) · Playwright (E2E) |
| Observabilité | Spring Actuator · Micrometer · Prometheus |
| CI/CD | GitHub Actions · SonarCloud · Semantic Release · Plumber |
| Déploiement | Docker · Docker Compose |

---

## Structure du dépôt

```
pivot/
├── backend/                   # Spring Boot (Maven)
│   ├── src/main/java/
│   ├── src/main/resources/
│   └── src/test/java/
├── frontend/                  # Angular
│   └── src/
├── docs/
│   ├── adr/                   # Architecture Decision Records
│   ├── gates/                 # Artifacts ACDD (us-{id}/gate-{n}.yaml)
│   └── audits/                # Audits par domaine
├── .github/
│   ├── workflows/
│   └── ISSUE_TEMPLATE/
├── .plumber.yaml              # Config Plumber (CI/CD compliance)
└── docker-compose.yml
```

---

## Équipe experte

Toute contribution mobilise les experts concernés — les mentionner explicitement dans la réponse.

| Expert | Domaine |
|--------|---------|
| **Architecte Java / Spring** | Architecture Spring Boot, patterns (Repository, Service, DTO), SOLID, modules |
| **Architecte Angular** | Architecture Angular, modules lazy-loaded, RxJS, OnPush, state management |
| **Architecte BDD PostgreSQL** | Schéma, migrations Flyway, index, performances, intégrité référentielle |
| **Expert DevSecOps** | CI/CD GitHub Actions, SonarCloud, Semgrep, Gitleaks, Plumber, SBOM, Semantic Release |
| **Expert Red Team** | OWASP Top 10, OIDC bypass, injection SQL, XSS, CSRF, IDOR, JWT attacks |
| **Expert Blue Team** | Spring Security hardening, CORS, CSP, audit log, réponse aux rapports Red Team |
| **Expert OIDC / IAM** | OIDC PKCE S256, Spring Security OAuth2 Resource Server, Keycloak, claims mapping, rôles |
| **Expert QA** | Stratégie TU/TI/E2E, Testcontainers, coverage ≥ 80 %, non-régression |
| **Expert RGPD** | Conformité RGPD/CNIL, bases légales, droits des personnes, registre Art. 30 |
| **Product Owner** | GitHub Issues backlog, Epics, US, critères d'acceptation, priorisation |
| **Scrum Master** | Coordination, sprints, impediments, backlog consistency |
| **Expert UX/UI** | Design system SCSS, accessibilité WCAG 2.1 AA, tokens CSS |
| **Architecte Modules** | Système de modules activables, registre, feature flags, isolation inter-modules |

### Faire appel aux experts

| Type de tâche | Expert(s) |
|---------------|-----------|
| Controller, Service, Repository Java | **Architecte Java / Spring** |
| Composant Angular, SCSS, routing | **Architecte Angular** + **Expert UX/UI** |
| Schéma BDD, migration Flyway, requête SQL | **Architecte BDD PostgreSQL** |
| Tests TU/TI/E2E, stratégie de couverture | **Expert QA** |
| CI/CD, GitHub Actions, Plumber, SBOM | **Expert DevSecOps** |
| Vulnérabilité sécurité, vecteur d'attaque | **Expert Red Team** → **Expert Blue Team** |
| OIDC, rôles, Spring Security config | **Expert OIDC / IAM** + **Expert Blue Team** |
| RGPD, consentement, droits des personnes | **Expert RGPD** |
| Backlog, US, acceptance criteria | **Product Owner** |
| Système de modules, registre, activation | **Architecte Modules** |
| Bug inexpliqué | **Architecte Java** en premier, puis **Expert Red Team** si suspicion sécurité |

**Règles :**
- Mentionner l'expert explicitement quand son domaine est engagé.
- Toute faille Red Team = correction Blue Team **avant** tout merge.
- Changement du contrat de module = tous les experts concernés.

---

## Backlog — GitHub Issues + Projects

> Projet open-source → GitHub Issues publiques (contributions externes possibles, roadmap visible, CI peut labeler automatiquement).

### Hiérarchie

```
Epic (issue parent, label "epic")
└── User Stories (issues enfants, label "us", liées via "tracked by")
```

### Template US (`.github/ISSUE_TEMPLATE/user-story.yml`)

```markdown
En tant que [admin / utilisateur / participant anonyme]
Je veux [action]
Afin de [bénéfice]

Critères d'acceptation :
- [ ] Given [contexte], when [action], then [résultat observable]
- [ ] Error case: given [input invalide], system retourne [erreur / status code]
- [ ] Security: [propriété de sécurité garantie]

Module cible : pivot-{module}
Estimation : XS / S / M / L / XL
Dépendances : #xxx (si applicable)
```

### Champs GitHub Projects

| Champ | Type | Valeurs |
|-------|------|---------|
| Status | Select | Backlog / Ready / In progress / Review / Done |
| Priority | Select | Critical / High / Medium / Low |
| Module | Select | core / whiteboard / session / roadmap / survey / quiz / auth / admin |
| Phase | Select | MVP / v1-enterprise / phase-3 |
| Size | Select | XS / S / M / L / XL |

### Mise à jour des statuts (Claude)

- Claude met le statut à **"Review"** quand une US est implémentée (Gate 2 vert)
- Alexandre valide → statut **"Done"**
- US bloquée → **"Backlog"** + note explicative
- Ne jamais laisser un statut obsolète

---

## Breaking Points — Validation humaine obligatoire

### Breaking Point 1 : Avant toute implémentation d'US

Demander explicitement la validation d'Alexandre sur **deux points** :

**1. L'US elle-même** — confirmer que c'est la prochaine à traiter :
> "Je m'apprête à implémenter `us-{slug}` (priorité X, estimation Y).
> Tu confirmes que c'est bien la prochaine, ou tu veux réorienter ?"

**2. Les critères d'acceptation** — présenter la liste et attendre le feu vert :
> "Voici les critères d'acceptation : [liste Given/When/Then].
> Tu valides, ou tu veux ajouter / modifier / retirer ?"

**Pourquoi :** critères mal cadrés en amont = code à refaire. Alexandre valide **avant** que Claude écrive la moindre ligne de code de production.

**Exceptions (pas de consultation requise) :**
- Correctifs sécurité sur vecteur exploitable immédiatement
- Syntaxe / linter / tests cassés bloquant la CI
- Bugs dont la cause racine est clairement identifiée

### Breaking Point 2 : Gate 4 MERGE < 60 ou hard block

Tout PR avec :
- Label `security` ou `breaking-change`
- Gitleaks secret détecté
- Modification du contrat de module sans PRs coordonnées
- Modification du système OIDC / rôles

→ Label `needs-human-review` + score breakdown + attendre Alexandre.

---

## Workflow — Ordre d'exécution par US

| Étape | Contenu |
|-------|---------|
| **1. Code** | Backend Java + JavaDoc · Frontend Angular + TSDoc |
| **2. Tests** | TU JUnit 5 + TI Testcontainers · Jest Angular — **dans le même commit** |
| **3. Qualité** | Checkstyle · SpotBugs · ESLint · TypeCheck verts |
| **4. UI / i18n / SCSS** | Composants Angular, styles, traductions |
| **5. Backlog** | Mettre à jour le statut de l'US dans GitHub Issues · **obligatoire avant commit** |
| **6. E2E** | Spec Playwright (happy path + 1 erreur critique) |
| **7. Commit** | `git add` fichier par fichier · commits atomiques · branche `feat/us-{id}-{slug}` |

> **E2E différable** si environnement indisponible. Étapes 5 et 7 non différables.

### Approche tests

Écrire le code d'abord, puis les tests couvrant toutes les branches et conditions limites. TDD strict non utilisé.

**Exception :** quand le contrat d'une API ou d'un service est flou — écrire les tests en premier pour forcer la clarification.

---

## Workflow — Vérifications avant push autonome

**Condition absolue avant tout push autonome : 0 erreur, 0 warning.**

Claude exécute ces commandes **sans attendre d'instruction** :

```bash
# Backend
cd backend && mvn verify -q        # compile + tests + Checkstyle + SpotBugs

# Frontend
cd frontend
npm run lint                        # ESLint + TypeCheck strict (0 warning)
npm run test:ci                     # Vitest coverage
npm run build -- --configuration production
```

Rapporter ✅ ou stderr complet. Toute erreur ou warning non justifié = **stop, corriger avant push**.

---

## Workflow — Branches

| Préfixe | Usage | Exemple |
|---------|-------|---------|
| `feat/us-{id}-{slug}` | Nouvelle US | `feat/us-42-module-survey` |
| `fix/{id}-{slug}` | Correction de bug | `fix/67-oidc-token-refresh` |
| `refactor/{id}-{slug}` | Refactoring | `refactor/89-module-registry` |
| `chore/{slug}` | CI, deps, config | `chore/plumber-config` |
| `docs/{slug}` | Documentation | `docs/adr-oidc-decision` |

**Règles :**
- Jamais de travail direct sur `main`
- Une branche = une US = une PR
- Rebase avant merge : `git rebase -i origin/main` → squash WIP
- `git push --force-with-lease` uniquement sur branches de travail

---

## Workflow — Commits

Format **Conventional Commits** (`type(scope): message`) — alimente Semantic Release pour le versioning automatique (`feat` → minor, `fix` → patch, `feat!` / `BREAKING CHANGE` → major).

| Commit | Contenu typique |
|--------|----------------|
| `chore(db):` | migrations Flyway, schéma |
| `feat(backend):` | service, repository, controller |
| `feat(api):` | endpoint REST, DTO |
| `feat(frontend):` | composant Angular, service, route |
| `feat(modules):` | registre de modules, feature flags |
| `feat(auth):` | OIDC, Spring Security, rôles |
| `feat(ws):` | WebSocket, STOMP handlers |
| `ci:` | GitHub Actions workflows, Plumber |
| `docs:` | README, CLAUDE.md, ADR |
| `security:` | correctif sécurité — **hard block Gate 4, review humaine** |

Co-author sur chaque commit : `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`

---

## Gates ACDD — Confidence Gates

Chaque gate produit un artifact YAML dans `docs/gates/us-{id}/`. Score 0–100, jamais booléen.

| Gate | Moment | Seuils |
|------|--------|--------|
| **1 — READINESS** | Avant implémentation | ≥ 70 → Breaking Point 1 · < 70 → clarification PO |
| **2 — COVERAGE** | Par commit | ≥ 85 → continuer · 70–84 → compléter tests · < 70 → stop |
| **3 — QUALITY** | Après CI verte | Hard blocks : secret Gitleaks, label `security`/`breaking-change`, modif contrat module/OIDC |
| **4 — MERGE CONFIDENCE** | Avant merge | ≥ 85 → merge autonome · 60–84 → merge documenté · < 60 → Breaking Point 2 |

**Checks Gate 1 :** AC testables (40) · dépendances résolues (20) · impact contrat module (15) · AC sécurité ≥ 1 (15) · pas de cycle (10)

**Checks Gate 2 :** AC couverts (50) · pas de code non testé (30) · tests non triviaux (20)

**Checks Gate 3 :** SonarCloud ≥ 80 % (25) · zéro finding critique/high (25) · linters clean (20) · Gitleaks clean (20) · build Docker (10)

**Format artifact** `docs/gates/us-{id}/gate-{n}.yaml` :
```yaml
gate: READINESS          # READINESS | COVERAGE | QUALITY | MERGE_CONFIDENCE
us_id: 42
score: 87
decision: VALIDATE_WITH_PO
executed_by: Claude
timestamp: 2026-06-19T10:00Z
breakdown: { ... }
notes: ""
```

---

## Agents IA — Rôles et cycle ACDD

### Philosophie

**ACDD (Acceptance Criteria Driven Development)** — gates de confiance continues.

- Gates → score (0–100), jamais booléen pass/fail
- Chaque gate → artifact YAML committé dans `docs/gates/` — pas réponse chat
- Breaking Points = seuls moments d'intervention humaine obligatoire
- En dehors des Breaking Points : Claude décide selon le score

### Rôles

| Agent | Responsabilité |
|-------|---------------|
| **PO Agent** | Génère Epics et US, rédige AC, clarifie AC ambigus |
| **Architect Agent** | Valide AC techniques, identifie impact contrat de module |
| **Security Agent** | Challenge AC (Red Team), valide fixes (Blue Team) |
| **Dev Agent** | Implémente sur branche dédiée, s'auto-évalue via gates |
| **QA Agent** | Rédige specs E2E, valide couverture, challenge gaps de tests |
| **PR Review Agent** | Exécute Gate 3 + Gate 4, merge ou escalade selon score |

### Cycle

```
PO Agent rédige Epic + US avec AC
    │
    ├── Architect Agent : faisabilité technique, impact contrat de module
    ├── Security Agent : couverture sécurité des AC
    ├── QA Agent : testabilité de chaque AC
    └── → Gate 1 READINESS
           │
           ├── Score ≥ 70 → Breaking Point 1 : validation Alexandre (PO)
           │     └── Alexandre valide → Dev Agent implémente
           └── Score < 70 → clarification PO Agent avant tout
```

### Format des AC

```markdown
- [ ] Given [contexte], when [action], then [résultat observable]
- [ ] Error case: given [input invalide], system retourne [erreur / status code]
- [ ] Security: [propriété de sécurité qui doit tenir]
```

Chaque AC mappe à au moins un test. AC sans test = non implémenté, peu importe le code présent.
AC ambigu à l'implémentation → **stopper et demander au PO Agent** — jamais d'interprétation unilatérale.

### Artifacts gates

Structure dans `docs/gates/us-{id}/` :
- `gate-1.yaml` — Readiness (avant implémentation)
- `gate-2-{commit}.yaml` — Coverage (après chaque commit)
- `gate-3.yaml` — Quality (après CI verte)
- `gate-4.yaml` — Merge confidence (décision finale)

### Labels PR

| Label | Signification |
|-------|--------------|
| `feat` | Nouvelle fonctionnalité |
| `fix` | Correction de bug |
| `security` | Impact sécurité — hard block Gate 4, review humaine |
| `breaking-change` | Changement de contrat — hard block Gate 4, review humaine |
| `module-contract` | Changement contrat de module — hard block Gate 4 |
| `needs-human-review` | Gate 4 < 60 ou hard block — décision humaine requise |
| `auto-approved` | Gate 4 ≥ 85 — mergé automatiquement |
| `chore` | Maintenance, CI, dépendances |
| `docs` | Documentation uniquement |

### Post-merge

```bash
# 1. Mettre à jour statut GitHub Issues → "Done"
# 2. Débloquer les US dépendantes
# 3. Nettoyer la branche
git push origin --delete feat/us-{id}-{slug}
```

---

## Standards de code

### Java (backend)

- JavaDoc sur toutes les classes et méthodes publiques
- Checkstyle (Google Style ou config projet)
- SpotBugs — aucun warning ignoré sans justification commentée
- Pas de logique dans les contrôleurs — déléguer aux services
- DTOs pour toutes les entrées/sorties API — **jamais les entités JPA directement**
- Pas de `@Transactional` sur les contrôleurs — uniquement sur les services

### Angular (frontend)

- TypeScript strict — pas de `any`
- OnPush change detection par défaut
- RxJS pour l'asynchrone — pas de Promise sauf interop
- SCSS BEM ou tokens centralisés — pas de styles inline
- WCAG 2.1 AA sur tous les éléments interactifs

### Général

- Pas de secrets dans le code — variables d'environnement
- Toute action state-changing → log structuré JSON (backend)
- `// NOSONAR — <justification courte>` (SonarCloud faux positif — justification obligatoire)
- `// nosemgrep: <rule-id>` (Semgrep faux positif)

---

## Système de modules

Contrat de base :

```java
public interface PivotModule {
    String getId();        // "whiteboard", "survey", "quiz"…
    String getName();      // nom affiché en UI
    String getVersion();
    boolean isEnabled(TenantContext ctx);  // activable par admin tenant
}
```

- Modules déclarés dans `backend/.../pivot/modules/registry/`
- Routes Angular lazy-loaded par module (`loadChildren`)
- Module désactivé = 403 côté API + module non chargé côté Angular
- Aucune logique inter-module directe — bus d'événements typés (`ApplicationEventPublisher`)
- Changement de contrat de module = **hard block Gate 4 + Breaking Point 2**

---

## Schéma de rôles (multi-tenant OIDC)

| Rôle | Périmètre | Droits |
|------|-----------|--------|
| `ROLE_SUPER_ADMIN` | Plateforme | Gestion des tenants, configuration globale |
| `ROLE_ADMIN` | Tenant | Activation modules, gestion utilisateurs tenant |
| `ROLE_USER` | Tenant | Utilisation des modules activés |
| `ROLE_GUEST` | Session | Participation anonyme (sessions live) |

Rôles portés via claims OIDC ou assignés localement. Le mapping claims → rôles est configurable par tenant.

---

## Audits

Dans `docs/audits/` — un fichier par catégorie, mis à jour en place. **Jamais de fichiers datés.**

| Catégorie | Fichier |
|-----------|---------|
| Sécurité applicative | `audit-cyber.md` |
| Architecture | `audit-architecture.md` |
| BDD PostgreSQL | `audit-bdd.md` |
| CI/CD / DevSecOps | `audit-cicd.md` |
| QA / Tests | `audit-qa.md` |
| RGPD | `audit-rgpd.md` |
| Modules / Plugins | `audit-modules.md` |
| UX / Accessibilité | `audit-ux.md` |

Historique des révisions en bas de chaque fichier :

```markdown
## Historique des révisions
| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | AAAA-MM-JJ | X.X/10 | Audit initial |
```

---

## Règles absolues

| Interdit | Raison |
|----------|--------|
| `--no-verify` | Contourne les hooks qualité |
| `git push --force` sur `main` | Jamais — Alexandre uniquement si nécessaire |
| `git add .` en bloc | Risque d'inclure `.env`, clés, binaires |
| Merger avec label `security` sans revue humaine | Hard block Gate 4 |
| Commiter `.env`, tokens, secrets, certificats | Exposition définitive |
| Entités JPA exposées directement en API | Fuite de schéma, IDOR |
| Logique métier dans les contrôleurs | Viole la séparation des couches |
| Module désactivé avec routes accessibles | Contournement restriction admin |
| Implémenter sans US tracée dans GitHub Issues | Perte de traçabilité |

---

## Boucles de problèmes — règle d'escalade

Après **2 tentatives** (même stratégie ou variantes proches) :
1. **Stopper** — ne pas continuer à boucler
2. **Committer l'artifact de gate** avec `decision: ESCALATED` et contexte complet
3. **Signaler** à Alexandre : blocage, tentatives, raison de l'échec — label `needs-human-review`
4. **Proposer** une alternative : approche différente, outil différent, contournement

Ne jamais enchaîner plus de 2 tentatives sans informer Alexandre.

---

## Skills — Knowledge Cards

Chaque skill est un document de référence domaine chargé contextuellement avant d'implémenter une US.
Index : `.project/skills/_index.yaml`

| Skill | Fichier | Charger quand |
|-------|---------|---------------|
| Spring Architecture | `skill-spring-architecture.yaml` | Tout fichier Java (Controller, Service, Repository, DTO) |
| Angular Architecture | `skill-angular-architecture.yaml` | Tout fichier .ts / .html / .scss |
| BDD & Flyway | `skill-bdd-flyway.yaml` | Migration Flyway, entité JPA, requête @Query |
| OIDC & Spring Security | `skill-oidc-security.yaml` | Fichier auth/, SecurityConfig, @PreAuthorize, AC sécurité |
| Module System | `skill-module-system.yaml` | Fichier modules/ ou registry/, route guard, US module |
| AC Traceability | `skill-ac-traceability.yaml` | **Toujours** — toute implémentation d'US, Gate 2, Gate 4 |
| Testing Strategy | `skill-testing-strategy.yaml` | Nouveau test, coverage < 80 %, spec Playwright |
| DevOps CI/CD | `skill-devops-cicd.yaml` | Fichier .github/workflows/, Dockerfile, config CI |
| Observabilité | `skill-observability.yaml` | Nouveau log, nouvelle métrique, endpoint health |
| RGPD | `skill-rgpd.yaml` | US touchant données personnelles (email, nom, contenu) |

**Règle :** avant d'écrire du code, identifier les skills applicables via l'index et les lire.
La skill `pivot-ac-traceability` est toujours chargée pour toute US.

---

## Parallelisation

Lancer un maximum d'actions en parallèle dans chaque message :

| Actions parallélisables | Exemples |
|------------------------|---------|
| Lectures indépendantes | Plusieurs `Read` / `Grep` / `Glob` |
| Linters | Checkstyle + SpotBugs + ESLint lancés simultanément |
| Créations de fichiers indépendants | TU + TI d'une même feature |
| Recherches codebase | Plusieurs `Grep` sur cibles différentes |

Ne séquencer que ce qui dépend du résultat d'une étape précédente.
