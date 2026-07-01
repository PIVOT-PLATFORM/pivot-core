# CLAUDE.md — PIVOT-CORE

## Projet

**PIVOT-CORE** — backend Java/Spring Boot de la suite collaborative PIVOT. Contient l'API REST, la base de données (PostgreSQL + Flyway), la sécurité (Spring Security + opaque tokens SHA-256 + OIDC resource server) et le système de modules.

Le frontend Angular est dans **pivot-ui**. La documentation générale du projet vit dans **pivot-docs**.

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
| Backend | Java 25 · Spring Boot 4.x · Maven · `--release 24` (pas de preview features) |
| BDD | PostgreSQL 18 · Spring Data JPA · Flyway |
| Cache / Temps réel | Redis · Spring WebSocket (STOMP) |
| Auth | Spring Security 7 · Opaque tokens SHA-256 (BDD) · OIDC resource server (spring-security-oauth2-jose) |
| Tests | JUnit 5 · Mockito · Testcontainers (TI) |
| Observabilité | Spring Actuator · Micrometer · Prometheus |
| CI/CD | GitHub Actions · SonarCloud · Semantic Release · Plumber |
| Déploiement | Docker · Docker Compose |
| Frontend | → **pivot-ui** (Angular 22 · TypeScript · SCSS · Vitest · Playwright) |

---

## Structure du dépôt

```
pivot-core/
├── src/                       # Spring Boot (Maven)
│   ├── main/java/
│   ├── main/resources/
│   │   ├── db/migration/      # Migrations Flyway (V1__, V2__…)
│   │   └── db/seeds/          # Seeds test (profil test uniquement)
│   └── test/java/
├── .github/
│   ├── workflows/
│   └── ISSUE_TEMPLATE/
├── .plumber.yaml              # Config Plumber (CI/CD compliance)
└── Dockerfile
```

Frontend Angular → **pivot-ui**. Documentation → **pivot-docs**.

---

## Équipe experte

Toute contribution mobilise les experts concernés — les mentionner explicitement dans la réponse.

| Expert | Domaine |
|--------|---------|
| **Architecte Java / Spring** | Architecture Spring Boot, patterns (Repository, Service, DTO), SOLID, modules |
| **Architecte BDD PostgreSQL** | Schéma, migrations Flyway, index, performances, intégrité référentielle |
| **Expert DevSecOps** | CI/CD GitHub Actions, SonarCloud, Semgrep, Gitleaks, Plumber, SBOM, Semantic Release |
| **Expert Red Team** | OWASP Top 10, OIDC bypass, injection SQL, CSRF, IDOR, JWT attacks |
| **Expert Blue Team** | Spring Security hardening, CORS, CSP, audit log, réponse aux rapports Red Team |
| **Expert OIDC / IAM** | OIDC PKCE S256, Spring Security OAuth2 Resource Server, Keycloak, claims mapping, rôles |
| **Expert QA** | Stratégie TU/TI, Testcontainers, coverage ≥ 85 %, non-régression |
| **Expert RGPD** | Conformité RGPD/CNIL, bases légales, droits des personnes, registre Art. 30 |
| **Product Owner** | Backlog markdown pivot-docs, Epics, US, critères d'acceptation, priorisation |
| **Scrum Master** | Coordination, sprints, impediments, backlog consistency |
| **Architecte Modules** | Système de modules activables, registre, feature flags, isolation inter-modules |
| **Expert PR Review** | Relecture croisée neutre : cohérence architecture, lisibilité, dette technique, respect des standards PIVOT — intervient quand les experts dev signalent "prêt pour review" |
| **Experts Angular / UX/UI** | → **pivot-ui** |

### Faire appel aux experts

| Type de tâche | Expert(s) |
|---------------|-----------|
| Controller, Service, Repository Java | **Architecte Java / Spring** |
| Schéma BDD, migration Flyway, requête @Query | **Architecte BDD PostgreSQL** |
| Tests TU/TI, Testcontainers, couverture | **Expert QA** |
| CI/CD, GitHub Actions, Plumber, SBOM | **Expert DevSecOps** |
| Vulnérabilité sécurité, vecteur d'attaque | **Expert Red Team** → **Expert Blue Team** |
| OIDC, rôles, Spring Security config | **Expert OIDC / IAM** + **Expert Blue Team** |
| RGPD, consentement, droits des personnes | **Expert RGPD** |
| Backlog, US, acceptance criteria | **Product Owner** |
| Système de modules, registre, activation | **Architecte Modules** |
| Review finale PR (après "prêt pour review") | **Expert PR Review** |
| Bug inexpliqué | **Architecte Java** en premier, puis **Expert Red Team** si suspicion sécurité |
| Frontend Angular, SCSS, composants | → **pivot-ui** |

**Règles :**
- Mentionner l'expert explicitement quand son domaine est engagé.
- Toute faille Red Team = correction Blue Team **avant** tout merge.
- Changement du contrat de module = tous les experts concernés **+ coordination pivot-core ↔ pivot-ui obligatoire**.

---

## Backlog — Fichiers markdown

> **Sources de vérité :**
> - Hiérarchie backlog + conventions : `pivot-docs/docs/backlog/README.md`
> - Sprints, assignation US, état avancement : **`pivot-docs/docs/backlog/SPRINTS.md`**
> - Backlog opérationnel : **fichiers markdown dans `pivot-docs/docs/backlog/`** — un fichier par US/Enabler avec frontmatter (`Stage`, `Priority`, `Phase`).

### Hiérarchie
`EPIC → FEATURE (valeur) / ENABLER (technique) → US` · clé `E01 → F01.1 / EN01.1 → US01.1.1`.

### Champs du Project

| Champ | Valeurs |
|-------|---------|
| Item Type | Epic / Feature / Enabler / US |
| Parent | clé du parent (ex. `E01`, `F01.1`) |
| Stage | Backlog / Ready / In progress / Review / Done |
| Priority | Critical / High / Medium / Low |
| Module | core / auth / admin / oidc / whiteboard / session / roadmap / survey / quiz (extensible) |
| Phase | MVP / v1-enterprise / phase-3 |
| Sprint | Sprint 1…N |
| Size | XS / S / M / L / XL |

### Template US, Definition of Ready, vagues → `pivot-docs/docs/backlog/README.md`.

---

## Breaking Points

### Step 0 — Challenge PO avant implantation

Avant tout code, le **PO Agent** challenge les ACs de l'US :

1. Verifier DoR — story complete, ACs Given/When/Then, AC erreur + securite
2. Calculer Gate 1 : **>= 70** -> proceder · **< 70** -> PO Agent recrit ACs -> recalculer
3. AC ambigus a l'implementation -> PO Agent clarifie, jamais d'interpretation unilaterale

Pas de blocage humain — Claude autonome de A a Z sur la validation des ACs.

### Breaking Point 2 : Gate 4 MERGE < 60 ou hard block

Tout PR avec :
- Label `security` ou `breaking-change`
- Gitleaks secret détecté
- Modification du contrat de module sans PRs coordonnées
- Modification du système OIDC / rôles

→ Label `needs-human-review` + score breakdown + attendre le mainteneur.

---

## Workflow — Organisation par sprint

Travail organisé par sprint. Référence : **`pivot-docs/docs/backlog/SPRINTS.md`**.

**Principes :**
- **Une branche par US / Enabler** — `feat/{us-id}-{slug}` (ex. `feat/us03-1-1-admin-active-module`)
- **Agents en parallèle** — un agent par item du sprint, branches séparées
- **Backlog pivot-docs sur la branche courante** — SPRINTS.md + PATCH_NOTES.md committés sur la branche de l'item (pas de branche docs séparée)

## Workflow — Autoloop PR par US

Après implémentation sur `feat/{us-id}-{slug}` :

1. Ouvrir une PR (draft) vers `main`
2. **Autoloop** (10 itérations max) :
   - **En parallèle :**
     - **Review neutre** — Expert PR Review : architecture, AC, sécurité, dette
     - **CI** — `mvn verify -q` = 0 erreur/warning · Gitleaks clean · Gate 3 hard blocks
   - **Corrections** — tous les findings résolus, commit `fix({scope}): ...`
   - **Convergence** — Gate 4 ≥ 85 ET CI verte → sortir
3. Gate 4 vert → `Stage: Review` dans frontmatter US + SPRINTS.md + signal mainteneur
4. Blocage 10 boucles → Breaking Point 2

## Workflow — Ordre d'exécution par US (dans un sprint)

| Étape | Contenu |
|-------|---------|
| **1. Code** | Java + JavaDoc |
| **2. Tests** | JUnit 5 TU + Testcontainers TI — **dans le même commit** |
| **3. Qualité** | Checkstyle · SpotBugs verts |
| **4. Gate 2** | Coverage check : ≥ 85 % → continuer · 70–84 % → compléter · < 70 % → stop |
| **5. Backlog** | Mise à jour SPRINTS.md + statut US **obligatoire avant commit** |
| **6. E2E** | — (délégué à pivot-ui) |
| **7. Commit** | `git add` fichier par fichier · commits atomiques sur branche `feat/{us-id}-{slug}` |

> **E2E délégué à pivot-ui.** Étapes 5 et 7 non différables (Backlog et Commit).

### Approche tests

Écrire le code d'abord, puis les tests couvrant toutes les branches et conditions limites. TDD strict non utilisé.

**Exception :** quand le contrat d'une API ou d'un service est flou — écrire les tests en premier pour forcer la clarification.

---

## Workflow — Vérifications avant push autonome

**Condition absolue avant tout push autonome : 0 erreur, 0 warning.**

Claude exécute ces commandes **sans attendre d'instruction** :

```bash
mvn verify -q        # compile + tests + Checkstyle + SpotBugs
```

Rapporter ✅ ou stderr complet. Toute erreur ou warning non justifié = **stop, corriger avant push**.

---

## Workflow — Branches

| Préfixe | Usage | Exemple |
|---------|-------|---------|
| `feat/{us-id}-{slug}` | Implémentation d'une US / Enabler | `feat/us03-1-1-admin-active-module` |
| `fix/{id}-{slug}` | Correction bug hors sprint | `fix/67-auth-redirect-loop` |
| `refactor/{id}-{slug}` | Refactoring hors sprint | `refactor/89-module-registry` |
| `chore/{slug}` | CI, deps, config | `chore/plumber-config` |
| `docs/{slug}` | Documentation hors sprint | `docs/adr-oidc-decision` |

**Règles :**
- Jamais de travail direct sur `main`
- **Une branche = un item de sprint** (US ou Enabler)
- **Backlog pivot-docs et PATCH_NOTES.md committés sur la branche de l'item courant**
- Rebase avant merge → squash WIP
- `git push --force-with-lease` uniquement sur branches de travail

**Création de branche item — procédure obligatoire :**
```bash
git checkout main
git pull origin main
git checkout -b feat/{us-id}-{slug}
```
Branche existante → `git checkout feat/{us-id}-{slug}` directement.

---

## Workflow — Commits

Format **Conventional Commits** (`type(scope): message`) — alimente Semantic Release pour le versioning automatique (`feat` → minor, `fix` → patch, `feat!` / `BREAKING CHANGE` → major).

| Commit | Contenu typique |
|--------|----------------|
| `feat(db):` | nouvelle migration Flyway (table, colonne, contrainte) → minor bump |
| `fix(db):` | correction migration Flyway existante → patch bump |
| `chore(db):` | seeds test, commentaires schéma (sans impact utilisateur) |
| `feat(backend):` | service, repository, controller |
| `fix(backend):` | correction bug backend |
| `feat(api):` | endpoint REST, DTO |
| `fix(api):` | correction endpoint ou contrat API |
| `test:` | ajout ou correction de tests (TU, TI) sans changement de code prod |
| `feat(modules):` | registre de modules, feature flags |
| `fix(modules):` | correction bug module system |
| `feat(auth):` | OIDC, Spring Security, rôles, opaque tokens |
| `fix(auth):` | correction bug auth / session |
| `feat(ws):` | WebSocket, STOMP handlers |
| `fix(ws):` | correction bug WebSocket / STOMP |
| `ci:` | GitHub Actions workflows, Plumber |
| `docs:` | README, CLAUDE.md, ADR |
| `security:` | correctif sécurité — **hard block Gate 4, review humaine** · label `security` posé automatiquement |

Co-author sur chaque commit : `Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>`

---

## Gates ACDD — Confidence Gates

Score 0–100, jamais booléen. Scores/décisions consignés en **commentaire de PR** (plus de
dossier `gates/`). Le statut vit dans le champ **Stage** du frontmatter US (pivot-docs).

| Gate | Moment | Seuils |
|------|--------|--------|
| **1 — READINESS** | Avant implémentation | PO Agent self-challenge · ≥ 70 → Stage: Ready → procéder · < 70 → PO Agent réécrit ACs |
| **2 — COVERAGE** | Par commit | ≥ 85 → continuer · 70–84 → compléter tests · < 70 → stop |
| **3 — QUALITY** | Après CI verte | Hard blocks : secret Gitleaks, label `security`/`breaking-change`, modif contrat module/OIDC |
| **4 — MERGE CONFIDENCE** | Avant merge | ≥ 85 → merge autonome · 60–84 → merge documenté · < 60 → Breaking Point 2 |

**Checks Gate 1 :** AC testables (40) · dépendances résolues (20) · impact contrat module (15) · AC sécurité ≥ 1 (15) · pas de cycle (10)

**Checks Gate 2 :** AC couverts (50) · pas de code non testé (30) · tests non triviaux (20)

**Checks Gate 3 :** SonarCloud ≥ 80 % (25) · zéro finding critique/high (25) · linters clean (20) · Gitleaks clean (20) · build Docker (10)

**Format du commentaire de PR (gate)** : `gate` (READINESS | COVERAGE | QUALITY | MERGE_CONFIDENCE), `score`, `decision`, `breakdown`, `notes`.

---

## Agents IA — Rôles et cycle ACDD

### Philosophie

**ACDD (Acceptance Criteria Driven Development)** — gates de confiance continues.

- Gates → score (0–100), jamais booléen pass/fail
- Chaque gate → consigné en **commentaire de PR** (pas de fichier committé)
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
           ├── Score ≥ 70 → Stage: Ready → procéder (PO Agent autonome)
           │     └── Le mainteneur valide → Dev Agent implémente
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

### Gates (commentaires de PR)

Chaque gate est consigné en **commentaire de PR** (plus de fichiers `gates/`) :
- Gate 1 Readiness (avant implémentation) — PO Agent valide ACs · ≥ 70 → Stage: Ready
- Gate 2 Coverage (après chaque commit)
- Gate 3 Quality (après CI verte)
- Gate 4 Merge confidence (décision finale)

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
# 1. Mainteneur : passe Stage → Done dans le frontmatter US (recette humaine — jamais Claude)
# 2. Débloquer les US dépendantes
# 3. Nettoyer la branche
git push origin --delete feat/{us-id}-{slug}
```

---

## Standards de code

### Java (backend)

- JavaDoc sur toutes les classes et méthodes publiques
- Checkstyle (Google Style ou config projet)
- SpotBugs — zéro warning ignoré · aucune suppression inline (`@SuppressFBWarnings`) sans validation explicite du mainteneur
- Pas de logique dans les contrôleurs — déléguer aux services
- DTOs pour toutes les entrées/sorties API — **jamais les entités JPA directement**
- Pas de `@Transactional` sur les contrôleurs — uniquement sur les services

### Général

- Pas de secrets dans le code — variables d'environnement
- Toute action state-changing → log structuré JSON (backend)
- **`// NOSONAR` : zéro, jamais.** Tout faux positif Sonar se marque côté SonarCloud (UI "Won't fix" / "False positive", ou exclusion centralisée) — aucune exception.
- **`// nosemgrep` : interdit par défaut**, autorisé **uniquement avec la validation explicite du mainteneur**. Sans validation, exclusion côté config Semgrep (`.semgrepignore` / `--exclude-rule`), jamais en commentaire inline.

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

- Modules déclarés dans `fr.pivot.modules.registry`
- Cache Redis : clé `module:{tenantId}:{moduleId}` · TTL 60s · invalidation à chaque changement d'état
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

## Authentification

| Mécanisme | Détail |
|-----------|--------|
| **Opaque tokens** | SHA-256 stocké en BDD (`access_tokens`), raw token jamais persisté, 256-bit SecureRandom, TTL en BDD — **pas de JWT** |
| **OIDC enterprise** | PKCE S256 côté Angular, validation JWKS côté backend via `spring-security-oauth2-jose` |
| **Access token** | En mémoire uniquement — **jamais dans Local Storage, sessionStorage, IndexedDB ou Cookie** |
| **Multi-tenant** | Un `TenantOidcConfig` par tenant, provisionnement JIT configurable |
| **Expiry** | Token expiré → backend retourne 401 · **raw token = valeur pre-hash 256-bit, jamais loguée ni persistée hors BDD** · aucun silent refresh via iframe |

---

## Releases — PATCH_NOTES.md

`PATCH_NOTES.md` (situé à la racine de `pivot-core/`) est mis à jour **dans chaque PR** (embarqué avec le code) :
- Ajouter les changements notables dans la section `## [Unreleased]` en tête de fichier
- Rédigé en **français**, pour l'utilisateur final — pas le développeur
- Langage naturel — pas de référence aux commits ou tickets
- Après la release SR : le script `.scripts/prepare-patch-notes.sh` renomme `[Unreleased]` automatiquement
- Fichier maintenu en place, **jamais de fichiers datés**
- **Exception** : PRs `chore` / `ci` / `docs` sans impact utilisateur visible — pas de mise à jour PATCH_NOTES

---

## Audits

Dans **pivot-docs** — un fichier par catégorie, mis à jour en place. **Jamais de fichiers datés.**

---

## Règles absolues

| Interdit | Raison |
|----------|--------|
| `--no-verify` | Contourne les hooks qualité |
| `git push --force` sur `main` | Jamais — le mainteneur uniquement si nécessaire |
| `git add .` en bloc | Risque d'inclure `.env`, clés, binaires |
| Merger avec label `security` sans revue humaine | Hard block Gate 4 |
| Commiter `.env`, tokens, secrets, certificats | Exposition définitive |
| Entités JPA exposées directement en API | Fuite de schéma, IDOR |
| Logique métier dans les contrôleurs | Viole la séparation des couches |
| Module désactivé avec routes accessibles | Contournement restriction admin |
| Implémenter sans US tracée dans les fichiers markdown backlog | Perte de traçabilité |
| JWT (HS*/RS*) comme mécanisme de session | Remplacé par opaque tokens TTL BDD |
| `tenantId` extrait du body / header dans un endpoint admin, superadmin ou module | IDOR cross-tenant — extrait exclusivement du `TenantContext` du token porteur |
| Valeur `userId` acceptée dans le body d'un endpoint `/api/account/*` | Mass assignment / IDOR — identité extraite du token porteur uniquement |
| `DELETE /api/admin/users/{userId}` sans vérification d'appartenance tenant | Cross-tenant user manipulation — toujours vérifier `user.tenantId == token.tenantId`, sinon 404 |

---

## Règle transversale sécurité — Isolation tenant

**Tout endpoint `/api/admin/*`, `/api/superadmin/*` et `/api/{module}/*` :**
- Extrait le `tenantId` **exclusivement** du `TenantContext` (injecté depuis le token porteur via Spring Security)
- N'accepte **jamais** un `tenantId` ou `userId` venant du body JSON, d'un query param ou d'un header custom
- Si `{userId}` ou `{resourceId}` dans le path → vérifier l'appartenance au tenant courant **avant** tout traitement
- Appartenance invalide → **404** (pas 403 — ne pas confirmer l'existence de la ressource cross-tenant)
- Test TI cross-tenant **obligatoire** sur chaque endpoint admin

---

## Boucles de problèmes — règle d'escalade

Après **2 tentatives** (même stratégie ou variantes proches) :
1. **Stopper** — ne pas continuer à boucler
2. **Poster un commentaire de gate sur la PR** avec `decision: ESCALATED`, contexte complet, tentatives effectuées — **jamais committer un fichier de gate**
3. **Signaler** au mainteneur : blocage, tentatives, raison de l'échec — label `needs-human-review`
4. **Proposer** une alternative : approche différente, outil différent, contournement

Ne jamais enchaîner plus de 2 tentatives sans informer le mainteneur.

---

## Skills — Knowledge Cards

Chaque skill est un document de référence domaine chargé contextuellement avant d'implémenter une US.
Index : `.project/skills/_index.yaml`

| Skill | Fichier | Charger quand |
|-------|---------|---------------|
| `skill-spring-architecture` | `skill-spring-architecture.yaml` | Tout fichier Java (Controller, Service, Repository, DTO) |
| `skill-bdd-flyway` | `skill-bdd-flyway.yaml` | Migration Flyway, entité JPA, requête @Query |
| `skill-oidc-security` | `skill-oidc-security.yaml` | Fichier auth/, SecurityConfig, @PreAuthorize, AC sécurité |
| `skill-module-system` | `skill-module-system.yaml` | Fichier modules/ ou registry/, US module |
| `skill-ac-traceability` | `skill-ac-traceability.yaml` | **Toujours** — toute implémentation d'US, Gate 2, Gate 4 |
| `skill-testing-strategy` | `skill-testing-strategy.yaml` | Nouveau test, coverage < 85 % (seuil Gate 2), Testcontainers |
| `skill-devops-cicd` | `skill-devops-cicd.yaml` | Fichier .github/workflows/, Dockerfile, config CI |
| `skill-observability` | `skill-observability.yaml` | Nouveau log, nouvelle métrique, endpoint health |
| `skill-rgpd` | `skill-rgpd.yaml` | US touchant données personnelles (email, nom, contenu) |
| `skill-i18n` | `skill-i18n.yaml` | MessageSource, emails multilingues, locale resolution, fallback |
| `skill-security-redteam` | `skill-security-redteam.yaml` | US touchant auth/admin/modules, nouvel endpoint REST, AC sécurité |
| `skill-security-blueteam` | `skill-security-blueteam.yaml` | Rapport Red Team reçu, SecurityConfig, mécanisme auth modifié |
| `skill-pr-reviewer` | `skill-pr-reviewer.yaml` | Gate 3 (qualité CI), Gate 4 (décision merge), review PR |

**Règle :** avant d'écrire du code, identifier les skills applicables via l'index et les lire.
La skill `skill-ac-traceability` est toujours chargée pour toute US.

---

## Parallélisation

Lancer un maximum d'actions en parallèle dans chaque message :

| Actions parallélisables | Exemples |
|------------------------|---------|
| Lectures indépendantes | Plusieurs `Read` / `Grep` / `Glob` |
| Linters | Checkstyle + SpotBugs lancés simultanément |
| Créations de fichiers indépendants | TU + TI d'une même feature |
| Recherches codebase | Plusieurs `Grep` sur cibles différentes |

Ne séquencer que ce qui dépend du résultat d'une étape précédente.
