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
ALTER TABLE tenants DROP CONSTRAINT chk_tenants_auth_mode;

ALTER TABLE tenants ADD CONSTRAINT chk_tenants_auth_mode
    CHECK (auth_mode IN ('SAAS', 'ENTERPRISE', 'HYBRID', 'LOCAL', 'OIDC', 'GOOGLE'));
