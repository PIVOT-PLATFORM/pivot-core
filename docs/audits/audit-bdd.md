# Audit BDD PostgreSQL

## Périmètre

Schéma, migrations Flyway, index, performances, intégrité référentielle, multi-tenant.

## Critères évalués

| Critère | Statut | Notes |
|---------|--------|-------|
| Flyway activé et configuré | ✅ OK | `application.yml` — `db/migration/` |
| `ddl-auto: validate` en prod | ✅ OK | `application.yml` |
| Nommage migrations `V{n}__{desc}.sql` | ⬜ À vérifier | Pas encore de migration |
| UUID comme PK (pas de séquence auto-incrémentée) | ⬜ À implémenter | Convention PIVOT |
| `TIMESTAMPTZ` pour toutes les dates | ⬜ À implémenter | — |
| `tenant_id` sur toutes les tables multi-tenant | ⬜ À implémenter | — |
| Index sur `tenant_id` + colonnes filtrées fréquemment | ⬜ À implémenter | — |
| `FetchType.LAZY` par défaut sur les relations JPA | ⬜ À implémenter | — |
| Pas de N+1 query non détecté | ⬜ À implémenter | — |
| Suppression en cascade correctement définie | ⬜ À implémenter | — |
| Pas de `open-in-view` | ✅ OK | Désactivé dans `application.yml` |

## Conventions PIVOT

- PK : `UUID DEFAULT gen_random_uuid()`
- Audit : colonnes `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ` sur toutes les tables
- Soft delete : colonne `deleted_at TIMESTAMPTZ NULL` (null = actif)
- Multi-tenant : `tenant_id UUID NOT NULL` + `FOREIGN KEY tenant_id REFERENCES tenants(id)`

## Historique des révisions

| Version | Date | Score | Évolutions principales |
|---------|------|-------|------------------------|
| v1 | 2026-06-20 | — | Audit initial — Flyway configuré, pas encore de schéma |
