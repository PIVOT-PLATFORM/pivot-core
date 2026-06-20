# ADR-003 — Système de modules activables

**Statut :** Accepté  
**Date :** 2026-06-19  
**Décideurs :** Alexandre Solane

---

## Contexte

PIVOT regroupe plusieurs outils (whiteboard, session, roadmap, survey, quiz). Les admins doivent pouvoir activer ou désactiver chaque outil par tenant, sans redéployer l'application.

## Options évaluées

| Approche | Isolation | Activation runtime | Complexité |
|----------|-----------|-------------------|------------|
| Microservices séparés | Forte | Via DNS/gateway | Très élevée |
| Feature flags (LaunchDarkly-style) | Faible | Oui | Moyenne |
| Interface `PivotModule` + registre Spring | Forte | Oui (par tenant) | Faible à moyenne |
| Plugins OSGi | Forte | Oui | Très élevée |

## Décision

**Interface `PivotModule` + registre Spring** — chaque module implémente un contrat minimal (`getId`, `getName`, `getVersion`, `isEnabled(TenantContext)`), se déclare comme bean Spring, et est interrogé par un registre central.

Règles d'isolation :
- Module désactivé → 403 côté API + module non chargé côté Angular (guard lazy route)
- Aucune logique inter-module directe → bus `ApplicationEventPublisher`
- Routes Angular lazy-loaded par module (`loadChildren`)

## Conséquences

**Positives :**
- Activation/désactivation sans redéploiement
- Isolation forte — un bug dans `quiz` ne casse pas `whiteboard`
- Extensible — ajout d'un module = implémenter l'interface + déclarer le bean

**Négatives / Contraintes :**
- Contrat `PivotModule` = point de couplage central — **toute modification = hard block Gate 4 + Breaking Point 2**
- Guard Angular doit être cohérent avec l'état backend — risque de désync si le cache Redis est périmé
