-- US06.2.2 — Super admin désactive un tenant
--
-- Stratégie de révocation en masse par génération : un unique horodatage sur le tenant
-- plutôt que de parcourir/révoquer chaque access_token individuellement (ce qui serait
-- O(n) utilisateurs et pourrait dépasser 500ms sur un tenant à forte volumétrie).
--
-- Chaque validation de token (voir TokenService#validate) doit désormais vérifier que
-- le token a été émis (created_at) APRÈS cet horodatage. NULL = tenant jamais désactivé,
-- tous ses tokens restent valides sur cette dimension (comportement par défaut, aucune
-- régression sur les tokens existants).
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS tenant_invalidation_timestamp TIMESTAMPTZ;

COMMENT ON COLUMN tenants.tenant_invalidation_timestamp IS
    'Horodatage de la dernière désactivation du tenant. Tout access_token dont created_at <= cette valeur est considéré révoqué, sans écriture individuelle sur access_tokens (révocation en O(1)). NULL = jamais désactivé.';
