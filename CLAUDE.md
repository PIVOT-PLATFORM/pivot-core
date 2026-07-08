# CLAUDE.md — PIVOT-CORE

## Projet

**PIVOT-CORE** — backend shell Java/Spring Boot de la suite collaborative PIVOT. Double rôle :

1. **Application shell** : API REST, sécurité (Spring Security + opaque tokens + OIDC resource server), gestion tenants/users/équipes, registre modules.
2. **Librairie Maven partagée** : publie `fr.pivot:pivot-core-starter` (GitHub Packages) — consommé par tous les repos `pivot-xxx-core`.

Le frontend Angular est dans **pivot-ui**. La documentation générale vit dans **pivot-docs**.

**Ce que pivot-core-starter exporte réellement** (état vérifié fichier par fichier, 2026-07-08,
`pivot-core#171` — ce tableau ne documente que ce qui existe, pas l'architecture cible) :

| Package | Contenu |
|---------|---------|
| `fr.pivot.core.tenant` | `TenantContext` uniquement. `TenantContextHolder`/`@TenantAware` différés — aucun consommateur réel identifié (tout le code passe `TenantContext` en paramètre explicite) |
| `fr.pivot.core.team` | Entités `Team`, `TeamMember` + repositories — partagées cross-modules. Pas d'API REST/logique métier tant qu'aucune US ne les spécifie |
| `fr.pivot.core.modules` | Interface `PivotModule`, registre, cache Redis, annotation `@RequiresModule` |
| `fr.pivot.core.db` | Flyway baseline schéma `public`, config DataSource multi-schéma |
| `fr.pivot.core.auth` | `AuthenticatedPrincipal` (record `userId`/`tenantId`/`role`) + interface `AuthenticatedPrincipalResolver` — principal minimal partagé (ADR-022), implémenté par `TokenService` |

**`fr.pivot.core.auth` — principal minimal extrait (ADR-022) ; validation elle-même non extraite.**
L'escalade `pivot-core#171` demandait deux décisions avant tout déplacement de code sur ce
composant de sécurité critique : la forme d'un principal minimal partagé, et validation dupliquée
(bibliothèque partagée) vs. centralisée (appel réseau vers `pivot-core`). ADR-022 tranche les
deux : `AuthenticatedPrincipal(userId, tenantId, role)` — exclut délibérément tout champ de profil
propre à `pivot-core-app` (email, hash mot de passe, 2FA, appareils de confiance, locale, avatar…)
— et validation dupliquée via bibliothèque partagée, jamais de centralisation réseau (contredirait
l'isolation de panne déjà documentée par les `CLAUDE.md` satellites). `TokenService` implémente
`AuthenticatedPrincipalResolver` ; `TokenAuthenticationFilter` reste inchangé (continue de peupler
`Authentication#getDetails()` avec l'entité `User` complète, dont dépendent une dizaine de
contrôleurs existants). La logique de validation elle-même (hash, expiration, révocation,
désactivation tenant/utilisateur) reste dans `pivot-core-app` — non dupliquée dans le starter par
cette extraction, aucun repo `pivot-xxx-core` n'ayant de logique métier implémentée à ce jour
(bootstrap infrastructure uniquement). Voir la Javadoc de `PivotCoreAutoConfiguration` (starter)
et l'ADR-022 (`pivot-docs/docs/adr/`) pour le raisonnement complet.

**Architecture BDD :** schéma `public` géré par pivot-core (Flyway). Les repos modules créent leur propre schéma Flyway et referencent `public.teams(id)` / `public.tenants(id)` par FK.

**Modules fonctionnels** : dans les repos dédiés (`pivot-pilotage-core`, `pivot-agilite-core`, `pivot-collaboratif-core`). pivot-core ne contient PAS de logique métier module.

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
├── src/
│   ├── main/java/fr/pivot/
│   │   ├── core/              # Packages exportés dans pivot-core-starter
│   │   │   ├── auth/          # Spring Security, opaque tokens, OIDC RS
│   │   │   ├── tenant/        # TenantContext, TenantContextHolder, @TenantAware
│   │   │   ├── team/          # Team, TeamMember (entités schéma public)
│   │   │   ├── modules/       # PivotModule interface, registre, @RequiresModule
│   │   │   └── db/            # Flyway config multi-schéma, DataSource
│   │   └── shell/             # Application shell (controllers, config Spring Boot)
│   ├── main/resources/
│   │   ├── db/migration/      # Flyway schéma public — voir règle V1 unique ci-dessous
│   │   └── db/seeds/          # Seeds test (profil test uniquement, V2__test_seeds.sql)
│   └── test/java/
├── .github/
│   ├── workflows/
│   └── ISSUE_TEMPLATE/
├── .plumber.yaml
└── Dockerfile
```

**Maven :** projet single-module. `pivot-core-starter` = artifact publié depuis ce même `pom.xml` via profil `release`. Les repos modules ajoutent `fr.pivot:pivot-core-starter` en dépendance.

**Migrations Flyway — fichier V1 unique avant la BETA :** tant que le schéma n'est pas stabilisé
(avant la première BETA du produit), tout changement de schéma est plié dans l'unique
`V1__schema_init.sql` plutôt que d'ajouter un `V2__`/`V3__…` séparé — pas d'historique de
migrations incrémentales à maintenir tant que rien n'est en prod. Ne pas créer de nouveau fichier
de migration numéroté sans feu vert explicite du mainteneur (déclenché au démarrage de la BETA).

Frontend Angular → **pivot-ui**. Documentation → **pivot-docs**. Logique métier modules → **pivot-xxx-core**.

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
> - Sprints, assignation US, état avancement : **`pivot-docs/docs/backlog/sprints/`** (un fichier par sprint, index dans `sprints/README.md`)
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
| Module | core / auth / admin / oidc / pilotage / agilite / collaboratif (extensible par domaine) |
| Phase | Socle / v1-enterprise / phase-3 |
| Sprint | Sprint 1…N |
| Size | XS / S / M / L / XL |

### Template US, Definition of Ready, vagues → `pivot-docs/docs/backlog/README.md`.

---

## Breaking Points

### Step 0 — Challenge PO avant implémentation

Avant tout code, le **PO Agent** challenge les ACs de l'US :

1. Vérifier DoR — story complète, ACs Given/When/Then, AC erreur + sécurité
2. Calculer Gate 1 : **= 100** → procéder · **< 100** → PO Agent réécrit ACs → recalculer
3. AC ambigus à l'implémentation → PO Agent clarifie, jamais d'interprétation unilatérale

Pas de blocage humain — Claude autonome de A à Z sur la validation des ACs.

### Breaking Point 2 : Gate 4 MERGE < 60 ou hard block

Tout PR avec :
- Label `security` ou `breaking-change`
- Gitleaks secret détecté
- Modification du contrat de module sans PRs coordonnées
- Modification du système OIDC / rôles

→ Label `needs-human-review` + score breakdown + attendre le mainteneur.

---

## Workflow — Organisation par sprint

Travail organisé par sprint. Référence : **`pivot-docs/docs/backlog/sprints/`** (un fichier par sprint).

**Principes :**
- **Une branche par US / Enabler** — `feat/{us-id}-{slug}` (ex. `feat/us03-1-1-admin-active-module`)
- **Agents en parallèle** — un agent par item du sprint, branches séparées
- **Backlog pivot-docs sur la branche courante** — `sprints/sprint-{N}.md` committé sur la branche de l'item (pas de branche docs séparée)
- **Issue GitHub liée** — avant de démarrer un item, vérifier qu'une issue existe dans **ce repo** pour cet US/Enabler (recherche par id/titre). Absente → la créer (titre `{id} — {titre US}`, corps = lien vers le fichier backlog pivot-docs + AC). **Déjà assignée** (humain ou agent en cours) → item déjà pris, ne pas démarrer, passer au suivant. Sinon → se l'auto-assigner immédiatement (`gh issue edit {N} --add-assignee @me`) avant le premier commit — verrouille l'item, empêche qu'un autre agent ou une autre personne ne le reprenne en parallèle. Référencer l'issue dans la PR (`Closes #N`) — fermeture automatique à la fusion, jamais de fermeture manuelle en double.

## Workflow — Merge séquentiel autonome (plusieurs PR)

Quand plusieurs PR sont ouvertes/en attente sur ce repo (ex. plusieurs items d'un même sprint),
Claude détermine seul l'ordre de fusion et l'exécute de bout en bout, sans confirmation par PR :

1. **Ordre** — dépendances fonctionnelles entre items d'abord, puis fichiers partagés
   (migration Flyway, config Spring commune) pour minimiser les rebases en cascade.
2. **Par PR, dans cet ordre :**
   - Rebase sur `main` à jour (jamais de merge commit)
   - Conflit → résolution manuelle réelle (jamais `--theirs`/`--ours` aveugle) : lire les deux
     côtés, comprendre l'intention de chacun, fusionner le contenu
   - Rebase sans conflit mais fichier partagé (ex. `application.yml`, schéma) → vérifier quand
     même qu'aucune régression sémantique silencieuse ne s'est introduite (ex. une clé de config
     écrasée par l'auto-merge git)
   - `mvn verify -q` local avant push (ou vérification équivalente si Docker indisponible en
     sandbox — s'appuyer sur la CI réelle pour la partie Testcontainers)
   - Push, attendre la CI réelle en boucle synchrone (jamais d'attente passive d'une notification)
   - Gate 4 selon les seuils déjà définis ci-dessous → squash-merge dès convergence
3. **Dernier item du sprint courant** (vérifier `pivot-docs/docs/backlog/sprints/sprint-{N}.md`)
   → le commit de squash-merge porte le marqueur de release (voir *Workflow — Release*
   ci-dessous), tous les autres non.
4. Incident CI (ex. course de versioning, package déjà publié) rencontré en cours de route →
   diagnostiquer et corriger avant de continuer la séquence, pas de contournement silencieux.

## Workflow — Release

Le déclenchement d'une release (`release.yml` : version, publish Maven/Docker, tag, changelog)
n'a lieu **qu'en fin de sprint**, jamais à chaque merge — un merge ordinaire ne doit ni bumper de
version ni publier quoi que ce soit.

- **Déclencheur** : le commit du squash-merge du **dernier item d'un sprint** porte le trailer
  `Release-Trigger: true` **sur sa propre ligne, seul, rien d'autre** (`grep -qxE` — match exact
  de ligne entière, jamais une simple sous-chaîne). Cette exactitude n'est pas cosmétique : une
  simple recherche de sous-chaîne matcherait n'importe quelle phrase qui *mentionne* le trailer
  (documentation, description de PR expliquant la fonctionnalité) — vécu en pratique le
  2026-07-06, sauvé de justesse par le fait que le commit fautif était de type `docs:` (aucune
  version calculée par semantic-release pour ce type, sinon la release se serait réellement
  déclenchée par accident).
- **Pourquoi** : avant cette règle, chaque merge déclenchait `release.yml` — plusieurs merges
  rapprochés calculaient tous la même "prochaine version" (aucun tag encore créé entre eux) et le
  second à publier échouait en conflit sur GitHub Packages (incident du 2026-07-06, versions
  0.22.0 puis 0.25.0 restées orphelines sans tag, cf. `CHANGELOG.md`).
- **Effet** : la release qui finit par se déclencher regroupe automatiquement, dans une seule
  entrée de changelog, tous les commits accumulés depuis le dernier tag — comportement natif de
  semantic-release (déjà observé sur `v0.22.0` et `v0.26.0`), pas une fonctionnalité à coder.
- **Ajout du trailer** : `gh pr merge --squash --body "...

Release-Trigger: true"` — trailer sur sa propre ligne finale, précédée d'une ligne vide, jamais
  intégré dans une phrase. Uniquement sur le merge identifié comme dernier item du sprint courant.

## Workflow — Autoloop PR

Après toute modification sur une branche de travail — US/Enabler (`feat/{us-id}-{slug}`) ou
hors sprint (`fix/`, `refactor/`, `chore/`, `docs/`) — **sans exception** :

1. Ouvrir une PR (draft) vers `main`
2. **Autoloop** (20 itérations max) :
   - **En parallèle :**
     - **Review neutre** — Expert PR Review : architecture, AC, sécurité, dette
     - **CI** — `mvn verify -q` = 0 erreur/warning · Gitleaks clean · Gate 3 hard blocks
   - **Corrections** — tous les findings résolus, commit `fix({scope}): ...`
   - **Convergence** — Gate 4 = 100/100 (ou convergence confirmée sans finding restant) ET CI verte → sortir
3. Gate 4 = 100/100 (ou convergence confirmée sans finding restant) :
   - Sortir la PR du mode draft (`gh pr ready`)
   - `Stage: Review` dans frontmatter US + `sprints/sprint-{N}.md` (backlog pivot-docs sur la branche courante, cf. règle ci-dessus — pas de branche docs séparée)
   - **Gate 5** — générer/mettre à jour la spec fonctionnelle et technique figée `pivot-docs/docs/specs/{EPIC}/{us-id}-{slug}.md` (branche/PR `pivot-docs` dédiée — jamais de commit cross-repo, voir `pivot-docs/docs/workflow/README.md`)
   - Signal mainteneur
4. Blocage 20 boucles → Breaking Point 2

## Workflow — Ordre d'exécution par US (dans un sprint)

| Étape | Contenu |
|-------|---------|
| **1. Code** | Java + JavaDoc |
| **2. Tests** | JUnit 5 TU + Testcontainers TI — **dans le même commit** |
| **3. Qualité** | Checkstyle · SpotBugs verts |
| **4. Gate 2** | Coverage check : ≥ 85 % → continuer · 70–84 % → compléter · < 70 % → stop |
| **5. Backlog** | Mise à jour `sprints/sprint-{N}.md` + statut US **obligatoire avant commit** |
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
| `feat/{us-id}-{slug}` | Implémentation d'une US | `feat/us03-1-1-admin-active-module` |
| `feat/{en-id}-{slug}` | Implémentation d'un Enabler | `feat/en03-1-module-interface` |
| `fix/{id}-{slug}` | Correction bug hors sprint | `fix/67-auth-redirect-loop` |
| `refactor/{id}-{slug}` | Refactoring hors sprint | `refactor/89-module-registry` |
| `chore/{slug}` | CI, deps, config | `chore/plumber-config` |
| `docs/{slug}` | Documentation hors sprint | `docs/adr-oidc-decision` |

**Règles :**
- Jamais de travail direct sur `main`
- **Une branche = un item de sprint** (US ou Enabler)
- **Backlog pivot-docs committé sur la branche de l'item courant**
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
| **1 — READINESS** | Avant implémentation | PO Agent self-challenge · = 100 → Stage: Ready → procéder · < 100 → PO Agent réécrit ACs |
| **2 — COVERAGE** | Par commit | ≥ 85 → continuer · 70–84 → compléter tests · < 70 → stop |
| **3 — QUALITY** | Après CI verte | Hard blocks : secret Gitleaks, label `security`/`breaking-change`, modif contrat module/OIDC |
| **4 — MERGE CONFIDENCE** | Avant merge | = 100/100 → sortie du mode draft (merge autonome) · 60–99 → merge documenté · < 60 → Breaking Point 2 |

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
           ├── Score = 100 → Stage: Ready → procéder (PO Agent autonome)
           │     └── Le mainteneur valide → Dev Agent implémente
           └── Score < 100 → clarification PO Agent avant tout
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
- Gate 1 Readiness (avant implémentation) — PO Agent valide ACs · = 100 → Stage: Ready
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
| `auto-approved` | Gate 4 = 100/100 — mergé automatiquement |
| `chore` | Maintenance, CI, dépendances |
| `docs` | Documentation uniquement |

### Post-merge

```bash
# 1. Fermer l'issue GitHub liée (si "Closes #N" dans la PR ne l'a pas déjà fait automatiquement)
# 2. Mainteneur : passe Stage → Done dans le frontmatter US (recette humaine — jamais Claude)
# 3. Débloquer les US dépendantes
# 4. Nettoyer la branche
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
| **Access token** | En mémoire uniquement — **jamais dans Local Storage, sessionStorage, IndexedDB ou Cookie** · Les cookies CSRF de protection du flux OIDC sont distincts et ne stockent pas le token |
| **Multi-tenant** | Un `TenantOidcConfig` par tenant, provisionnement JIT configurable |
| **Expiry** | Token expiré → backend retourne 401 · **raw token = valeur pre-hash 256-bit, jamais loguée ni persistée hors BDD** · aucun silent refresh via iframe |

---

## Audits

Dans **pivot-docs** — un fichier par catégorie, mis à jour en place. **Jamais de fichiers datés.**

---

## Règles absolues

| Interdit | Raison |
|----------|--------|
| `--no-verify` | Contourne les hooks qualité |
| `git push origin main` (push direct) | Jamais — tout code passe par PR + review |
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

### Limite 10 commandes en échec successif

Si **10 commandes consécutives échouent** (toute combinaison : build, test, lint, push, CI) sur une tâche :
1. **Stopper la tâche courante** — ne pas impacter les agents parallèles sur d'autres US
2. **Poster un commentaire de gate** avec `decision: ESCALATED`, liste des 10 échecs, contexte
3. **Label `needs-human-review`** + signal mainteneur
4. **Proposer une alternative** (approche différente, découpage)

Le compteur se remet à zéro dès qu'une commande réussit.

### Limite 20 push — autoloop PR Review

Voir section **Workflow — Autoloop PR** — au-delà de 20 push correctifs → Breaking Point 2 automatique.

### Règle 2 tentatives (stratégie identique)

Après **2 tentatives** (même stratégie ou variantes proches) :
1. **Stopper** — ne pas continuer à boucler
2. **Poster un commentaire de gate sur la PR** avec `decision: ESCALATED`, contexte complet, tentatives effectuées — **jamais committer un fichier de gate**
3. **Signaler** au mainteneur : blocage, tentatives, raison de l'échec — label `needs-human-review`
4. **Proposer** une alternative : approche différente, outil différent, contournement

Ne jamais enchaîner plus de 2 tentatives sans informer le mainteneur.

---

## Template Review PR uniforme

Toutes les reviews de PR (Gate 4) postées en commentaire GitHub suivent ce template exact.
Charger `skill-pr-reviewer` avant d'écrire le commentaire.

```markdown
## PR Review — Gate 4

**US :** {us-id} — {titre}
**Score : {score}/100**
**Décision : MERGE_AUTONOMOUS | MERGE_DOCUMENTED | NEEDS_HUMAN_REVIEW**

### Breakdown
| Dimension | Score | Détail |
|-----------|-------|--------|
| Architecture (Controller/Service/Repository/DTO, JavaDoc) | /25 | |
| Traçabilité AC (AC → test, coverage Gate 2) | /25 | |
| Sécurité (isolation tenant, secrets, test cross-tenant) | /25 | |
| Qualité (Checkstyle/SpotBugs verts) | /25 | |

### Traçabilité AC
| AC | Implémentation | Test | Statut |
|----|----------------|------|--------|
| AC-{id}-01 | ... | ... | ✅/⬜ |

### Gate 3 — hard blocks
- [ ] Gitleaks clean
- [ ] CI 0 erreur / 0 warning
- [ ] Pas de secret committé
- [ ] Pas de `breaking-change` non documenté
- [ ] Pas de modification contrat module/OIDC sans coordination pivot-ui

### Findings
| # | Sévérité | Fichier | Description | Correction |
|---|----------|---------|--------------|------------|

### Notes
{notes libres}
```

**Règles d'application :**
- Posté uniquement en **commentaire PR** — jamais de fichier committé
- Score calculé dimension par dimension (0–25 chacune) — voir `gate_scoring` dans `skill-pr-reviewer.yaml`
- Findings classés : 🔴 Bloquant · 🟡 Mineur · 🔵 Cohérent
- Un finding 🔴 = itération obligatoire, quel que soit le score total

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
