# Audit Modules & Plugins

## Périmètre

Système de modules activables, registre, isolation, feature flags, contrat `PivotModule`.

## Modules prévus

| Module | Status | Description |
|--------|--------|-------------|
| `core` | ⬜ À implémenter | Fonctionnalités transverses (auth, profil, tenant) |
| `whiteboard` | ⬜ À implémenter | Tableau blanc collaboratif temps réel |
| `session` | ⬜ À implémenter | Sessions live : QUIZ, POLL, WORDCLOUD, BRAINSTORM, QA |
| `roadmap` | ⬜ À implémenter | Roadmap / Gantt |
| `survey` | ⬜ À implémenter | Sondages |
| `quiz` | ⬜ À implémenter | Quiz interactif gamifié |

## Critères évalués

| Critère | Statut | Notes |
|---------|--------|-------|
| Interface `PivotModule` définie | ✅ OK | `modules/registry/PivotModule.java` |
| `TenantContext` défini | ✅ OK | `modules/registry/TenantContext.java` |
| Registre de modules Spring (`@Component`) | ⬜ À implémenter | — |
| Module désactivé = 403 API | ⬜ À implémenter | Filtre Spring Security |
| Module désactivé = non chargé Angular | ⬜ À implémenter | Guard lazy route |
| Aucune logique inter-module directe | ⬜ Convention | Bus `ApplicationEventPublisher` |
| Routes Angular lazy-loaded par module | ⬜ À implémenter | `loadChildren` dans `app.routes.ts` |
| Admin peut activer/désactiver par tenant | ⬜ À implémenter | US admin à créer |

## Contrat de base (référence)

```java
public interface PivotModule {
    String getId();
    String getName();
    String getVersion();
    boolean isEnabled(TenantContext ctx);
}
```

Toute modification de ce contrat = **hard block Gate 4 + Breaking Point 2**.

## Historique des révisions

| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | 2026-06-20 | 1/10 | Audit initial — contrat posé, aucun module implémenté |
