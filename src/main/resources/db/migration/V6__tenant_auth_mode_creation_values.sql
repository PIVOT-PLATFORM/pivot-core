-- US06.2.1 — Super admin crée un tenant.
--
-- tenants.auth_mode was originally modeled as a deployment-scope concept
-- (SAAS | ENTERPRISE | HYBRID — see V1__schema_init.sql comment: "drives available auth
-- methods"). The tenant-creation form (US06.2.1) reuses the same column to let a super
-- admin pick the *primary authentication method* offered to a new tenant's users at
-- creation time: LOCAL (email/password), OIDC (enterprise SSO) or GOOGLE.
--
-- Both value sets are kept (additive, non-breaking): the bootstrap "pivot-saas" tenant
-- (V1 seed) and any existing row keep using the original SAAS/ENTERPRISE/HYBRID values,
-- while tenants created via POST /api/superadmin/tenants use LOCAL/OIDC/GOOGLE. No rows
-- need to be migrated — this only widens the CHECK constraint.
--
-- Flyway version-slot collision (merge-order note for the maintainer): PR #135
-- (US06.2.2, "super admin désactive un tenant") independently adds its own
-- V4__tenant_invalidation_timestamp.sql on top of the same origin/main base. Flyway
-- rejects two migrations sharing one version, so whichever of this PR / #135 merges
-- second must renumber its V4 migration (e.g. to V5) before merging — no data-model
-- conflict between the two migrations themselves, purely a version-number clash.
ALTER TABLE tenants DROP CONSTRAINT chk_tenants_auth_mode;

ALTER TABLE tenants ADD CONSTRAINT chk_tenants_auth_mode
    CHECK (auth_mode IN ('SAAS', 'ENTERPRISE', 'HYBRID', 'LOCAL', 'OIDC', 'GOOGLE'));
