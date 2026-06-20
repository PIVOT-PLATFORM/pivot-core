# Audit UX / Accessibilité

## Périmètre

Design system SCSS, accessibilité WCAG 2.1 AA, tokens CSS, composants Angular.

## Critères évalués

| Critère | Statut | Notes |
|---------|--------|-------|
| SCSS configuré | ✅ OK | Angular CLI — `styles.scss` |
| Tokens CSS centralisés | ⬜ À créer | `src/styles/tokens.scss` |
| Design system (couleurs, typographie, spacing) | ⬜ À créer | — |
| Pas de styles inline dans les composants | ⬜ Convention | BEM ou tokens uniquement |
| WCAG 2.1 AA — contraste couleurs ≥ 4.5:1 | ⬜ À vérifier | Pas de composant encore |
| WCAG 2.1 AA — navigation clavier | ⬜ À implémenter | Focus visible sur tous les éléments interactifs |
| WCAG 2.1 AA — attributs ARIA | ⬜ À implémenter | — |
| Lighthouse Accessibilité ≥ 90 | ⬜ À mesurer | Workflow Lighthouse non créé |
| Responsive (mobile first) | ⬜ Convention | — |
| `OnPush` change detection par défaut | ⬜ Convention | Aucun composant métier encore |

## Conventions PIVOT

- Tokens : `--pivot-color-primary`, `--pivot-spacing-*`, `--pivot-font-*`
- BEM : `.pivot-{module}__element--modifier`
- Icônes : SVG inline ou Material Icons — pas d'icon font
- Breakpoints : `sm 640px`, `md 768px`, `lg 1024px`, `xl 1280px`

## Historique des révisions

| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | 2026-06-20 | — | Audit initial — Angular posé, aucun composant métier |
