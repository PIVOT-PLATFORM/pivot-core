## US liée

Closes #

## Description

<!-- Résumé des changements -->

## Type de changement

- [ ] `feat` — nouvelle fonctionnalité
- [ ] `fix` — correction de bug
- [ ] `security` — **hard block : review humaine obligatoire**
- [ ] `breaking-change` — **hard block : review humaine obligatoire**
- [ ] `refactor` — refactoring sans changement de comportement
- [ ] `chore` — maintenance, CI, dépendances
- [ ] `docs` — documentation

## Couverture des AC

| Critère d'acceptation | Test(s) associé(s) |
|-----------------------|--------------------|
| Given … when … then … | `TestClass#testMethod` |
| Error case : … | `TestClass#testErrorMethod` |
| Security : … | `TestClass#testSecurityMethod` |

## Gate de confiance

- Gate 1 READINESS : `docs/gates/us-{id}/gate-1.yaml`
- Gate 2 COVERAGE (dernier commit) : `docs/gates/us-{id}/gate-2-{sha}.yaml`
- Gate 3 QUALITY (après CI) : `docs/gates/us-{id}/gate-3.yaml`
- Gate 4 score : ___/100

## Checklist

- [ ] Tous les AC couverts par des tests
- [ ] Linter clean (Checkstyle + ESLint)
- [ ] Aucun secret dans le code
- [ ] JavaDoc sur classes et méthodes publiques
- [ ] Migration Flyway créée si changement BDD
- [ ] Spec Playwright ajoutée / mise à jour (happy path + 1 erreur critique)
- [ ] Statut GitHub Issues mis à jour → "Review"
- [ ] Artifact Gate 2 committé dans `docs/gates/`
