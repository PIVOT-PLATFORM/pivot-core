# Handoff — extraction du domaine `pilotage` vers un produit distinct (EN53.3)

> Vague 3 de la migration modulith (ADR-030). `pilotage` (pilotage de projet : roadmap, Gantt,
> portefeuille, calendrier) **quitte PIVOT**. Ce document est le **contrat de reprise** : ce qui a
> été retiré de PIVOT, et comment démarrer le nouveau produit à partir des repos `pivot-pilotage-*`
> en préservant l'historique git.
>
> ⚠️ **Actions irréversibles NON exécutées ici** (attendent une décision explicite du mainteneur) :
> extraction git réelle vers de nouveaux repos, archivage des repos `pivot-pilotage-*`, drop du
> schéma `pilotage` en base réelle. Cette PR ne fait que **retirer les références** côté PIVOT.

## 1. Ce qui a été retiré de PIVOT (cette vague)

| Repo | Retrait |
|------|---------|
| `pivot-core` | entrée catalogue `pilotage` (`application.yml`), service `pivot-pilotage-core` (`compose.yml`/`docker-compose.prod.yml`), job `prometheus.yml`, topic/DLQ `activemq.xml`, scripts `dev-*.sh`/`pack-local-ui.sh`, mentions Javadoc/tests cosmétiques du starter, docs |
| `pivot-ui` | `providePilotageUi`, `PILOTAGE_ROUTE` + `pilotage-module-loader.ts`, dep `@pivot-platform/pilotage-ui`, `pilotageApiUrl`, entrée `merge-module-i18n`, upstreams nginx `/api/pilotage`+`/ws/pilotage`, `bump-lib` |
| `pivot-infra` | `module "run_pilotage"` (Cloud Run) + variables/outputs, provisioning du schéma `pilotage` (`cloud-sql`) |

Aucune de ces suppressions n'impacte `agilite`/`collaboratif` (isolation par schéma, aucune FK
entrante vers `pilotage`, aucune dépendance Maven/npm croisée).

## 2. Contenu à extraire

| Repo source | Contenu | Volume |
|-------------|---------|--------|
| `pivot-pilotage-core` | backend Java/Spring Boot — domaines `schedule/`+`schedule/engine` (moteur d'ordonnancement Gantt), `gantt/`, `roadmap/`, `portfolio/`, `baseline/`, `dashboard/`, `calendar/`, `weather/`, `consolidation/`, `profile/` | ~349 classes, 39 commits |
| `pivot-pilotage-ui` | frontend Angular — lib `@pivot-platform/pilotage-ui` (roadmap, Gantt WBS/dépendances/baselines/scheduling, calendriers, feature `portfolio`) | Angular, 38 commits |

## 3. Extraction en préservant l'historique git

Les repos `pivot-pilotage-core`/`pivot-pilotage-ui` sont **autonomes** (repos git à part entière) —
l'extraction la plus simple préserve déjà tout l'historique :

```bash
# Option A (recommandée) — les repos sont déjà séparés : cloner puis re-pointer l'origine.
git clone https://github.com/PIVOT-PLATFORM/pivot-pilotage-core.git <nouveau-produit>-core
cd <nouveau-produit>-core && git remote set-url origin git@github.com:<ORG-NOUVEAU-PRODUIT>/<repo>.git
git push -u origin main   # historique complet conservé

# Option B — si on veut regrouper back+front dans un monorepo du nouveau produit :
git subtree add --prefix=core  https://github.com/PIVOT-PLATFORM/pivot-pilotage-core.git main
git subtree add --prefix=ui    https://github.com/PIVOT-PLATFORM/pivot-pilotage-ui.git   main
```

Pas besoin de `git filter-repo` (aucun code pilotage n'a jamais été mélangé dans `pivot-core` —
pilotage était déjà un repo isolé, contrairement à ce qu'imaginait le plan initial).

## 4. Contrat de reprise (dépendances à recréer côté nouveau produit)

1. **Auth** — ⚠️ point le plus important. `pivot-pilotage-core` n'a **jamais** consommé
   `fr.pivot:pivot-core-starter` : il utilise des `DenyAll*EditPolicy` (aucune validation de token
   réelle, tout en lecture/deny). Le nouveau produit **doit implémenter sa propre authentification**
   (ou consommer un contrat partagé). Rien à « débrancher » — tout est à **brancher**.
2. **Contrat partagé optionnel** — si le nouveau produit veut réutiliser `pivot-core-starter`
   (principal auth minimal `AuthenticatedPrincipal`, entités `Team`/`TeamMember`, système de modules),
   il faut soit accéder à l'artefact publié (`fr.pivot:pivot-core-starter` sur GitHub Packages
   PIVOT-PLATFORM), soit en vendorer une copie. **PIVOT continue de publier `pivot-core-starter`**
   tant que ce besoin existe (décidé en ADR-030).
3. **Base de données** — schéma `pilotage` (tables `roadmap_projects`, `portfolio_items`,
   `schedule*`, `baselines`, `calendar*`, etc. + FK vers `public.teams`/`public.tenants`). Avant tout
   drop côté PIVOT : `pg_dump --schema=pilotage` → restaurer dans la base du nouveau produit. Les FK
   vers `public.teams`/`public.tenants` deviennent des tables/colonnes propres au nouveau produit
   (il n'y a plus de `public` PIVOT partagé).
4. **Frontend** — `@pivot-platform/pilotage-ui` dépend de `@pivot-platform/design-system` +
   `@pivot-platform/ui-core` (publiés GitHub Packages). Le nouveau produit consomme ces packages ou
   les vendore. Deps **exclusives** à emporter : `html2canvas@^1.4.1`, `jspdf@^4.2.1` (export PDF
   roadmap/Gantt). Tokens d'injection : `PILOTAGE_API_URL`. Route publique sans garde :
   `roadmap-shares/:token`.
5. **CI/CD** — les repos `pivot-pilotage-*` ont déjà leurs workflows (`release.yml`, `deploy.yml`,
   `notify-*`, `bump-lib`, scorecard/security/sbom). Ils servent de socle CI graine ; adapter
   l'organisation GitHub cible, les secrets (GitHub Packages, GCP), et retirer la mécanique
   `notify-pivot-ui` (le shell PIVOT n'est plus le consommateur).
6. **Point à vérifier au repli** — composant orphelin `portfolio/weather-indicator`
   (`pivot-pilotage-ui`, présent dans `lib/features/portfolio` mais **non routé** par
   `PILOTAGE_ROUTES`) : confirmer s'il est repris ou supprimé.

## 5. Séquence recommandée (côté mainteneur, après merge de cette vague)

1. `pg_dump --schema=pilotage` de la base recette-managed (sauvegarde avant tout drop).
2. Créer les repos du nouveau produit ; pousser `pivot-pilotage-*` (Option A/B ci-dessus).
3. Câbler auth + dépendances (§4) dans le nouveau produit ; CI verte de son côté.
4. **Puis seulement** : drop du schéma `pilotage` en base PIVOT + `git archive`/archivage lecture
   seule des repos `pivot-pilotage-core`/`pivot-pilotage-ui` (irréversible — confirmation mainteneur).
