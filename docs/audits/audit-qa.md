# Audit QA & Tests

## Périmètre

Stratégie TU/TI/E2E, couverture, non-régression, Testcontainers, Jest, Playwright.

## Critères évalués

| Critère | Statut | Notes |
|---------|--------|-------|
| JUnit 5 configuré | ✅ OK | Spring Boot test starter inclus |
| Testcontainers (pas H2) | ⬜ À vérifier | Dépendance à ajouter dans `pom.xml` |
| Jest configuré (Angular) | ✅ OK | Angular CLI l'inclut |
| Playwright configuré | ⬜ À ajouter | `playwright.config.ts` manquant |
| Coverage ≥ 80 % sur nouveau code | ⬜ À vérifier | Pas encore de tests |
| Jacoco configuré (backend) | ⬜ À ajouter | Plugin Maven manquant dans `pom.xml` |
| SonarCloud reçoit les rapports coverage | ✅ OK | `sonar-project.properties` configuré |
| Nommage tests `ac{id}_{n}_...` (Java) | ⬜ Convention | Aucun test encore |
| Nommage tests `AC-{id}-{n}:` (Jest) | ⬜ Convention | Aucun test encore |
| `data-testid` sur éléments Playwright | ⬜ Convention | Aucun composant encore |
| E2E happy path + 1 erreur critique par US | ⬜ Convention | — |

## Seuils Gate 2

| Niveau | Action |
|--------|--------|
| ≥ 85 | Continuer |
| 70–84 | Écrire tests manquants avant de continuer |
| < 70 | Stop — consulter stratégie QA |

## Actions prioritaires

1. Ajouter `testcontainers-bom` + `testcontainers-postgresql` dans `pom.xml`
2. Ajouter plugin Jacoco dans `pom.xml` (profil `coverage`)
3. Créer `playwright.config.ts` dans `frontend/`
4. Premier test TI avec Testcontainers dès la première migration Flyway

## Historique des révisions

| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | 2026-06-20 | — | Audit initial — framework en place, 0 test métier |
