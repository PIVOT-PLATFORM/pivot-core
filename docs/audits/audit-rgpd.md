# Audit RGPD

## Périmètre

Conformité RGPD/CNIL, bases légales, droits des personnes, rétention, registre Art. 30.

## Données personnelles traitées

| Donnée | Finalité | Base légale | Rétention |
|--------|----------|-------------|-----------|
| Email utilisateur | Authentification, notifications | Contrat | Durée du compte + 30j |
| Nom / prénom | Affichage profil | Contrat | Durée du compte + 30j |
| Contenu sessions live (réponses, votes) | Fonctionnalité collaborative | Contrat | Configurable par admin |
| Logs d'activité | Sécurité, audit | Intérêt légitime | 12 mois |
| Adresse IP | Sécurité | Intérêt légitime | 12 mois |

## Critères évalués

| Critère | Statut | Notes |
|---------|--------|-------|
| Registre des traitements (Art. 30) | ⬜ À créer | — |
| Base légale documentée par traitement | ⬜ Partiel | Tableau ci-dessus = draft |
| Droit d'accès implémenté | ⬜ À implémenter | `GET /api/v1/me/data` |
| Droit de rectification | ⬜ À implémenter | `PUT /api/v1/me/profile` |
| Droit à l'effacement | ⬜ À implémenter | `DELETE /api/v1/me` |
| Droit à la portabilité | ⬜ À implémenter | `GET /api/v1/me/export` |
| Pas de DCP dans les logs | ⬜ À vérifier | Convention skill-rgpd |
| Rétention configurable par admin | ⬜ À implémenter | — |
| Consentement explicite si nécessaire | ⬜ À implémenter | Fonctionnalités optionnelles |
| Mention RGPD dans la politique de confidentialité | ⬜ À créer | — |

## Historique des révisions

| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | 2026-06-20 | 1/10 | Audit initial — traitements identifiés, aucune implémentation |
