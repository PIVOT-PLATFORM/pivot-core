# Audit Architecture

## Périmètre

Architecture globale PIVOT : couches applicatives, patterns, séparation des responsabilités, couplage inter-modules.

## Critères évalués

| Critère | Statut | Notes |
|---------|--------|-------|
| Séparation Controller / Service / Repository | ⬜ À vérifier | Pas encore de code métier |
| DTOs sur tous les endpoints API | ⬜ À vérifier | — |
| Pas de logique dans les contrôleurs | ⬜ À vérifier | — |
| Modules isolés via bus d'événements | ⬜ À vérifier | Interface `PivotModule` posée |
| Pas de dépendances circulaires inter-modules | ⬜ À vérifier | — |
| Entités JPA non exposées directement | ⬜ À vérifier | — |
| `@Transactional` sur services uniquement | ⬜ À vérifier | — |
| JavaDoc sur classes et méthodes publiques | ⬜ À vérifier | — |

## Points d'attention

- Le système de modules (`PivotModule` + `TenantContext`) est le contrat central — toute modification = hard block Gate 4
- Le bus d'événements (`ApplicationEventPublisher`) est le seul canal inter-modules autorisé

## Historique des révisions

| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | 2026-06-20 | — | Audit initial — scaffold posé, pas de code métier |
