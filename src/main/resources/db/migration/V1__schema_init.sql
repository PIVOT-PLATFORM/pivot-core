-- pivot-platform schema v1 — consolidated DDL
-- Source of truth for fresh installs
--
-- Kept as a single consolidated file by convention until the product's first BETA —
-- see CLAUDE.md. Do not add V2+ migrations before that point; fold new DDL in here instead.

-- ----------------------------------------------------------------
-- EXTENSIONS
-- ----------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ================================================================
-- TABLE: tenants
-- ================================================================
CREATE TABLE IF NOT EXISTS tenants (
    id          BIGSERIAL    NOT NULL,
    slug        VARCHAR(100) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    plan        VARCHAR(50)  NOT NULL DEFAULT 'SAAS',
    -- Originally a deployment-scope concept (SAAS | ENTERPRISE | HYBRID — drives available
    -- auth methods). Tenant creation (US06.2.1) reuses the same column to let a super admin
    -- pick the *primary authentication method* offered to a new tenant's users at creation
    -- time: LOCAL (email/password), OIDC (enterprise SSO) or GOOGLE. Both value sets coexist
    -- (additive, non-breaking): the bootstrap "pivot-saas" tenant (seed below) and any
    -- deployment-scope row keep using SAAS/ENTERPRISE/HYBRID, while tenants created via
    -- POST /api/superadmin/tenants use LOCAL/OIDC/GOOGLE.
    auth_mode   VARCHAR(20)  NOT NULL DEFAULT 'SAAS',
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    -- Horodatage de la dernière désactivation du tenant. Tout access_token dont created_at <=
    -- cette valeur est considéré révoqué, sans écriture individuelle sur access_tokens
    -- (révocation en O(1) plutôt que O(n) utilisateurs). NULL = jamais désactivé, aucune
    -- régression sur les tokens existants.
    tenant_invalidation_timestamp TIMESTAMPTZ,
    -- US03.3.1 — module-bundling/pricing-tier plan this tenant is subscribed to. Deliberately
    -- NOT named "plan_id": the column above named "plan" is the legacy deployment-scope/
    -- auth-mode enum (SAAS/ENTERPRISE/TRIAL, see its comment) — a bare "plan_id" here would read
    -- as if it FKs that same column, which it does not. "billing_plan_id" makes the pricing/
    -- module-bundle semantics unambiguous. Nullable: not every tenant has a billing plan
    -- assigned yet (this US only introduces plan definition, not tenant enrollment UX).
    -- No inline FK here: the "plans" table this references is only created further below (after
    -- "module_activations", to keep module-system-adjacent tables together) — see the
    -- fk_tenants_billing_plan ALTER TABLE right after the "plan_modules" table block for the FK
    -- constraint itself, added once "plans" exists.
    billing_plan_id BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_tenants PRIMARY KEY (id),
    CONSTRAINT uq_tenants_slug UNIQUE (slug),
    CONSTRAINT chk_tenants_plan CHECK (plan IN ('SAAS', 'ENTERPRISE', 'TRIAL')),
    CONSTRAINT chk_tenants_auth_mode CHECK (auth_mode IN ('SAAS', 'ENTERPRISE', 'HYBRID', 'LOCAL', 'OIDC', 'GOOGLE'))
);

CREATE INDEX IF NOT EXISTS idx_tenants_slug ON tenants (slug);
CREATE INDEX IF NOT EXISTS idx_tenants_billing_plan_id ON tenants (billing_plan_id);

-- ================================================================
-- TABLE: tenant_oidc_configs
-- ================================================================
-- One or more OIDC configs per tenant (multi-IdP).
-- When present, SAAS auth (password/Google) disabled.
CREATE TABLE IF NOT EXISTS tenant_oidc_configs (
    id                   BIGSERIAL     NOT NULL,
    tenant_id            BIGINT        NOT NULL,
    issuer_uri           VARCHAR(500)  NOT NULL,
    client_id            VARCHAR(255)  NOT NULL,
    -- Encrypted at application level (AES-256-GCM)
    client_secret_enc    TEXT,
    scopes               VARCHAR(500)  NOT NULL DEFAULT 'openid email profile',
    -- Readable name for admin: "Azure AD", "Okta Production"…
    name                 VARCHAR(255)  NOT NULL DEFAULT 'Default IdP',
    -- Email domains mapped to this IdP, comma-separated (e.g. "company.com,corp.company.com")
    allowed_domains      VARCHAR(1000),
    -- JSON mapping of IdP claims → PIVOT fields
    claims_mapping       JSONB         NOT NULL DEFAULT '{"email":"email","first_name":"given_name","last_name":"family_name"}',
    -- true = auto-create account on first login (JIT provisioning)
    auto_provision_users BOOLEAN       NOT NULL DEFAULT true,
    -- Role assigned to JIT-provisioned accounts
    default_role         VARCHAR(50)   NOT NULL DEFAULT 'ROLE_USER',
    -- true = PKCE public flow (SPA), false = confidential backend flow
    pkce_required        BOOLEAN       NOT NULL DEFAULT true,
    -- Azure AD only: validate "tid" claim. NULL = non-Azure.
    azure_tenant_id      VARCHAR(36),
    -- Disable an IdP without deleting it (maintenance, migration)
    is_active            BOOLEAN       NOT NULL DEFAULT true,
    -- Override JWKS URI for IdPs behind corporate firewalls.
    -- NULL = standard auto-discovery via issuer_uri.
    jwks_uri             VARCHAR(500),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_tenant_oidc_configs PRIMARY KEY (id),
    CONSTRAINT fk_oidc_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

-- Index for email-domain routing and active IdP lookup
CREATE INDEX IF NOT EXISTS idx_oidc_tenant_active ON tenant_oidc_configs (tenant_id, is_active);

-- ================================================================
-- TABLE: users
-- ================================================================
CREATE TABLE IF NOT EXISTS users (
    id                          BIGSERIAL    NOT NULL,
    tenant_id                   BIGINT       NOT NULL,
    email                       VARCHAR(320) NOT NULL,
    -- NULL for OAuth2/OIDC-only accounts
    password_hash               VARCHAR(255),
    first_name                  VARCHAR(100),
    last_name                   VARCHAR(100),
    role                        VARCHAR(50)  NOT NULL DEFAULT 'ROLE_USER',
    email_verified              BOOLEAN      NOT NULL DEFAULT false,
    -- Google OAuth2 link
    google_id                   VARCHAR(255),
    -- OIDC external subject (from enterprise IdP)
    oidc_subject                VARCHAR(500),
    is_active                   BOOLEAN      NOT NULL DEFAULT true,
    is_blocked                  BOOLEAN      NOT NULL DEFAULT false,
    -- Preferred language (i18n). Inherited from browser on first login. Also exposed and
    -- user-editable via the account profile API (US02.1.2, `preferredLanguage`) — deliberately
    -- not a separate column: `locale` and "preferred language" are the same fact about the
    -- user, a second column would create two sources of truth and risk drift between the UI
    -- language and the language of emails sent to the same user.
    locale                      VARCHAR(10)  NOT NULL DEFAULT 'fr',
    -- Profile photo URL (provided by Google OAuth / OIDC)
    avatar_url                  TEXT,
    -- Brute-force protection. Lockout is currently enforced solely by the Redis
    -- sliding-window rate limiter (RateLimiterService); these columns are RESERVED
    -- for a future durable audit / admin-unblock feature and are not yet written.
    failed_login_attempts       INT          NOT NULL DEFAULT 0,
    locked_until                TIMESTAMPTZ,
    last_login_at               TIMESTAMPTZ,
    inactivity_warning_sent_at  TIMESTAMPTZ,
    -- Soft delete RGPD Art. 17. deleted_at is set IMMEDIATELY when deletion is requested,
    -- marking the account "PENDING_DELETION": invisible to admin reads and unresolvable at
    -- login. scheduled_deletion_at is the effective purge date communicated to the user
    -- (grace period deadline). anonymized_at marks that the scheduled purge has actually
    -- anonymized the row — distinguishes "pending, still within grace period" from "purged"
    -- without parsing users.email.
    deleted_at                  TIMESTAMPTZ,
    scheduled_deletion_at       TIMESTAMPTZ,
    anonymized_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    -- RegisterRequest.locale is already constrained to fr|en at the application layer
    -- (@Pattern "^(fr|en)$"), and no code path other than registration writes this column
    -- (no Google/OIDC claim is ever mapped onto it) — this promotes that existing invariant
    -- to the database, per this schema's convention for enum-like columns.
    CONSTRAINT chk_users_locale CHECK (locale IN ('fr', 'en'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_tenant_email ON users (tenant_id, email);
CREATE INDEX IF NOT EXISTS idx_users_google_id ON users (google_id);
CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users (deleted_at);
CREATE INDEX IF NOT EXISTS idx_users_oidc_subject ON users (oidc_subject);

-- Feeds AccountDeletionScheduler.anonymizeDueAccounts() — accounts whose grace period elapsed
-- and have not been anonymized yet.
CREATE INDEX IF NOT EXISTS idx_users_scheduled_deletion ON users (scheduled_deletion_at)
    WHERE deleted_at IS NOT NULL AND anonymized_at IS NULL;

-- ================================================================
-- TABLE: email_verifications
-- ================================================================
CREATE TABLE IF NOT EXISTS email_verifications (
    id          BIGSERIAL   NOT NULL,
    user_id     BIGINT      NOT NULL,
    -- SHA-256 hash of the raw token sent by email
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_email_verifications PRIMARY KEY (id),
    CONSTRAINT uq_ev_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_ev_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ev_token_hash ON email_verifications (token_hash);
CREATE INDEX IF NOT EXISTS idx_ev_user_id ON email_verifications (user_id);

-- ================================================================
-- TABLE: password_reset_tokens
-- ================================================================
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id          BIGSERIAL   NOT NULL,
    user_id     BIGINT      NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_password_reset_tokens PRIMARY KEY (id),
    CONSTRAINT uq_prt_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_prt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_prt_token_hash ON password_reset_tokens (token_hash);
CREATE INDEX IF NOT EXISTS idx_prt_user_id ON password_reset_tokens (user_id);

-- ================================================================
-- TABLE: trusted_devices
-- ================================================================
-- Confirmed devices — trust survives session revocations. Sliding window TTL (DEVICE_TTL_DAYS).
CREATE TABLE IF NOT EXISTS trusted_devices (
    id                 BIGSERIAL    NOT NULL,
    user_id            BIGINT       NOT NULL,
    device_fingerprint VARCHAR(64)  NOT NULL,
    device_name        VARCHAR(255),
    ip_address         VARCHAR(45),
    confirmed_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_trusted_devices PRIMARY KEY (id),
    CONSTRAINT uq_td_user_device UNIQUE (user_id, device_fingerprint),
    CONSTRAINT fk_td_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_td_user_id ON trusted_devices (user_id);

-- ================================================================
-- TABLE: device_verify_tokens
-- ================================================================
-- MFA email OTP for login from unknown device. TTL: DEVICE_VERIFY_TTL_MINUTES (default 15 min).
CREATE TABLE IF NOT EXISTS device_verify_tokens (
    id                 BIGSERIAL   NOT NULL,
    user_id            BIGINT      NOT NULL,
    device_fingerprint VARCHAR(64) NOT NULL,
    device_name        VARCHAR(255),
    -- SHA-256 of the 6-digit OTP sent by email
    otp_hash           VARCHAR(64) NOT NULL,
    attempts           INT         NOT NULL DEFAULT 0,
    expires_at         TIMESTAMPTZ NOT NULL,
    confirmed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_device_verify_tokens PRIMARY KEY (id),
    CONSTRAINT fk_dvt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_dvt_user_device ON device_verify_tokens (user_id, device_fingerprint);

-- ================================================================
-- TABLE: suspicious_login_tokens
-- ================================================================
-- "Not me" single-use link token emailed on a passive suspicious-login alert (US01.4.3a).
-- Distinct from device_verify_tokens: that one is a 6-digit OTP typed in BEFORE a login from an
-- unknown device is allowed to complete (a blocking gate, only active when MFA_NEW_DEVICE_OTP is
-- enabled or the account is ROLE_SUPER_ADMIN). This one fires AFTER a login already succeeded
-- from a device unknown to trusted_devices while that gate did not apply — a passive, non-
-- blocking notification. Shape mirrors password_reset_tokens (raw 256-bit value hashed with
-- SHA-256, expires_at/used_at) rather than device_verify_tokens' HMAC OTP, since this token is
-- clicked from an email link, never typed in by hand. TTL: 1h (SUSPICIOUS_LOGIN_OTP_TTL_MINUTES).
CREATE TABLE IF NOT EXISTS suspicious_login_tokens (
    id                 BIGSERIAL   NOT NULL,
    user_id            BIGINT      NOT NULL,
    device_fingerprint VARCHAR(64) NOT NULL,
    device_name        VARCHAR(255),
    ip_address         VARCHAR(45),
    token_hash         VARCHAR(64) NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    used_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_suspicious_login_tokens PRIMARY KEY (id),
    CONSTRAINT uq_slt_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_slt_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_slt_token_hash ON suspicious_login_tokens (token_hash);
CREATE INDEX IF NOT EXISTS idx_slt_user_id ON suspicious_login_tokens (user_id);

-- ================================================================
-- TABLE: audit_events
-- ================================================================
-- Immutable RGPD Art. 30 audit log — no DELETE from application
CREATE TABLE IF NOT EXISTS audit_events (
    id          BIGSERIAL   NOT NULL,
    -- ON DELETE RESTRICT — accountability RGPD Art. 5.2
    user_id     BIGINT,
    tenant_id   BIGINT,
    event_type  VARCHAR(64) NOT NULL,
    -- ip_address is nullable (no default) — set only when available
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    meta        JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_audit_events PRIMARY KEY (id),
    CONSTRAINT fk_audit_user   FOREIGN KEY (user_id)   REFERENCES users (id),
    CONSTRAINT fk_audit_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE INDEX IF NOT EXISTS idx_audit_user_id    ON audit_events (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_event_type ON audit_events (event_type);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON audit_events (created_at);

-- ================================================================
-- TABLE: feature_flags
-- ================================================================
-- Super-admin toggles for platform-wide features.
-- Typed model: bool | int | float, configurable via /admin/feature-flags.
CREATE TABLE IF NOT EXISTS feature_flags (
    id          BIGSERIAL    NOT NULL,
    flag_key    VARCHAR(100) NOT NULL,
    -- Enabled toggle (kept for boolean flags backward compat)
    enabled     BOOLEAN      NOT NULL DEFAULT false,
    description TEXT,
    -- Serialized value: "true"/"false" for bool, "86400" for int, "0.5" for float
    value       VARCHAR(255) NOT NULL DEFAULT 'false',
    -- Controls deserialization: bool | int | float
    type        VARCHAR(10)  NOT NULL DEFAULT 'bool',
    -- Human-readable label for admin UI
    label       VARCHAR(128),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_feature_flags PRIMARY KEY (id),
    CONSTRAINT uq_feature_flags_key UNIQUE (flag_key),
    CONSTRAINT fk_ff_user FOREIGN KEY (updated_by) REFERENCES users (id)
);

-- ================================================================
-- TABLE: access_tokens
-- ================================================================
-- Opaque session tokens. One token per session: 256-bit SecureRandom value (hex-encoded),
-- stored as its SHA-256 hash. Admin-configurable TTL, automatic renewal via Spring Security filter.
CREATE TABLE IF NOT EXISTS access_tokens (
    id                 BIGSERIAL   NOT NULL,
    -- Owner account — cascade delete if account deleted
    user_id            BIGINT      NOT NULL,
    -- SHA-256 of raw token — raw token is never persisted
    token_hash         VARCHAR(64) NOT NULL,
    -- Device identifier (browser fingerprint)
    device_fingerprint VARCHAR(64),
    device_name        VARCHAR(255),
    user_agent         TEXT,
    -- IPv4 or IPv6 — audit only
    ip_address         VARCHAR(45),
    -- Initial authentication method
    auth_method        VARCHAR(20) NOT NULL DEFAULT 'password',
    -- true = extended TTL (SESSION_TTL_REMEMBER_ME_SECONDS)
    remember_me        BOOLEAN     NOT NULL DEFAULT false,
    -- Token status
    status             VARCHAR(10) NOT NULL DEFAULT 'active',
    -- TTL captured at creation from feature_flag.
    -- Stored here to compute renewal threshold without querying feature_flags on every validation.
    ttl_seconds        INT         NOT NULL,
    -- Updated on each validated request — sliding window audit
    last_used_at       TIMESTAMPTZ,
    -- Absolute expiry date calculated at creation
    expires_at         TIMESTAMPTZ NOT NULL,
    -- Revocation timestamp (audit). NULL = not revoked.
    revoked_at         TIMESTAMPTZ,
    -- Set when the token has been rotated. The row stays 'active' for a short grace
    -- window (SESSION_ROTATION_GRACE_SECONDS) so in-flight concurrent requests holding
    -- the old token still authenticate, then it expires naturally.
    rotated_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_access_tokens PRIMARY KEY (id),
    CONSTRAINT fk_at_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_at_status CHECK (status IN ('active', 'expired', 'revoked')),
    CONSTRAINT chk_at_auth_method CHECK (auth_method IN ('password', 'google', 'oidc'))
);

-- Primary lookup on every authenticated API request (UNIQUE — SHA-256 collision-resistant)
CREATE UNIQUE INDEX IF NOT EXISTS idx_at_token_hash ON access_tokens (token_hash);

-- Revocation of all sessions for a user
CREATE INDEX IF NOT EXISTS idx_at_user_status ON access_tokens (user_id, status);

-- Partial index for purge cron (targets only revoked/expired rows ~small %)
CREATE INDEX IF NOT EXISTS idx_at_status_cleanup ON access_tokens (status)
    WHERE status IN ('revoked', 'expired');

-- Partial index for active sessions per user (countByUserIdAndStatus + MAX_SESSIONS eviction)
CREATE INDEX IF NOT EXISTS idx_at_active_user_created ON access_tokens (user_id, created_at)
    WHERE status = 'active';

-- ================================================================
-- TABLE: module_activations
-- ================================================================
-- EN03.1 — état d'activation des modules PIVOT par tenant (schéma public).
-- Une ligne par couple (tenant, module). Absence de ligne = module désactivé.
CREATE TABLE IF NOT EXISTS module_activations (
    id          BIGSERIAL    NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    module_id   VARCHAR(100) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_module_activations PRIMARY KEY (id),
    -- Un seul état par couple (tenant, module) — sert aussi d'index de lookup
    -- (préfixe tenant_id) pour findAllByTenantId / findByTenantIdAndModuleId.
    CONSTRAINT uq_ma_tenant_module UNIQUE (tenant_id, module_id),
    -- CASCADE justifié : l'état d'activation n'a aucun sens sans son tenant.
    CONSTRAINT fk_ma_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

-- ================================================================
-- TABLE: plans
-- ================================================================
-- US03.3.1 — commercial/pricing plan definitions (SUPER_ADMIN-managed): which PIVOT modules
-- are bundled in a given plan. Distinct from tenants.plan (legacy deployment-scope / primary
-- auth-mode enum, see comment on that column above) — DO NOT confuse the two. A tenant's
-- module-bundle plan is tracked via tenants.billing_plan_id (added further above, once this
-- table exists — see that column's comment for why the FK constraint is added via ALTER TABLE
-- rather than inline).
CREATE TABLE IF NOT EXISTS plans (
    id          BIGSERIAL    NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_plans PRIMARY KEY (id),
    CONSTRAINT uq_plans_name UNIQUE (name)
);

-- ================================================================
-- TABLE: plan_modules
-- ================================================================
-- M-N association: modules bundled in a plan. module_id is a plain identifier (not FK'd to a
-- DB table) — modules are declared in the in-code ModuleRegistry, not persisted as rows, same
-- convention as module_activations.module_id.
CREATE TABLE IF NOT EXISTS plan_modules (
    id          BIGSERIAL    NOT NULL,
    plan_id     BIGINT       NOT NULL,
    module_id   VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_plan_modules PRIMARY KEY (id),
    CONSTRAINT uq_pm_plan_module UNIQUE (plan_id, module_id),
    CONSTRAINT fk_pm_plan FOREIGN KEY (plan_id) REFERENCES plans (id) ON DELETE CASCADE
);

-- Forward-reference FK: tenants.billing_plan_id is declared with the tenants table at the top
-- of this file (before "plans" exists), so the FK constraint itself is added here, immediately
-- after "plans" is created. RESTRICT (not CASCADE/SET NULL) — a plan currently referenced by a
-- tenant cannot be silently deleted (no orphaned-tenant-plan U/X ambiguity); the maintainer must
-- reassign the tenant's plan first.
ALTER TABLE tenants
    ADD CONSTRAINT fk_tenants_billing_plan FOREIGN KEY (billing_plan_id) REFERENCES plans (id);

-- ================================================================
-- TABLE: module_overrides
-- ================================================================
-- US03.3.2 — SUPER_ADMIN override d'activation d'un module par tenant.
--
-- Concept DISTINCT de module_activations (voir son commentaire ci-dessus) : module_activations
-- porte le choix de l'admin *du tenant* (autorité tenant-scope, EN03.1) ; module_overrides porte
-- une décision plateforme du SUPER_ADMIN qui prend explicitement le pas dessus (autorité
-- cross-tenant). Conflater les deux dans une seule table/ligne mélangerait deux niveaux
-- d'autorité différents : un admin de tenant qui active/désactive son module écraserait
-- silencieusement une décision super-admin (ou l'inverse) si les deux partageaient la même
-- ligne. Une table séparée garantit que ModuleActivationService#isEnabled peut composer les
-- deux sources sans ambiguïté : override présent → il gagne toujours ; absent → repli sur
-- module_activations (voir ModuleActivationService pour la résolution complète).
--
-- Une ligne par couple (tenant, module) = un override actif, `enabled` porte la valeur forcée.
-- Absence de ligne = pas d'override, comportement normal (module_activations). Contrairement à
-- module_activations, `enabled` n'a pas de DEFAULT : chaque ligne n'existe que via un appel
-- explicite à POST .../override avec une valeur assumée, jamais une création implicite.
CREATE TABLE IF NOT EXISTS module_overrides (
    id          BIGSERIAL    NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    module_id   VARCHAR(100) NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_module_overrides PRIMARY KEY (id),
    -- Un seul override par couple (tenant, module) — sert aussi d'index de lookup
    -- (préfixe tenant_id).
    CONSTRAINT uq_mo_tenant_module UNIQUE (tenant_id, module_id),
    -- CASCADE justifié : un override n'a aucun sens sans son tenant (même motif que
    -- fk_ma_tenant ci-dessus).
    CONSTRAINT fk_mo_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

-- ================================================================
-- TABLE: email_change_requests
-- ================================================================
-- US02.2.2 — changement d'adresse email : lien de confirmation à usage unique.
-- Mêmes garanties que email_verifications / password_reset_tokens : le token brut
-- n'est jamais persisté, seul son hash SHA-256 l'est (voir EmailChangeService).
--
-- Une seule ligne "en attente" (used_at IS NULL AND cancelled_at IS NULL) par utilisateur
-- à la fois : toute nouvelle demande annule (cancelled_at) la précédente avant d'en créer
-- une nouvelle. L'ancienne adresse (users.email) reste inchangée tant que le lien envoyé
-- à la nouvelle adresse n'a pas été confirmé.
CREATE TABLE IF NOT EXISTS email_change_requests (
    id           BIGSERIAL    NOT NULL,
    user_id      BIGINT       NOT NULL,
    -- Nouvelle adresse demandée — appliquée à users.email uniquement après confirmation.
    new_email    VARCHAR(320) NOT NULL,
    -- SHA-256 hash du token brut envoyé par email — le brut n'est jamais persisté.
    token_hash   VARCHAR(64)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    -- Renseigné au clic valide sur le lien de confirmation (usage unique).
    used_at      TIMESTAMPTZ,
    -- Renseigné quand une nouvelle demande supersède celle-ci avant toute confirmation.
    cancelled_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_email_change_requests PRIMARY KEY (id),
    CONSTRAINT uq_ecr_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_ecr_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ecr_token_hash ON email_change_requests (token_hash);
CREATE INDEX IF NOT EXISTS idx_ecr_user_id ON email_change_requests (user_id);

-- ================================================================
-- TABLE: data_export_requests
-- ================================================================
-- RGPD Art. 20 (portabilité) — US02.3.1. Une ligne par demande d'export de
-- données personnelles (POST /api/account/export). Le fichier généré (ZIP)
-- est stocké sur le système de fichiers local (chemin configurable, voir
-- pivot.export.storage-path) ; seul le hash SHA-256 du token de
-- téléchargement à usage unique est persisté, jamais le token brut — même
-- convention que access_tokens / password_reset_tokens.
CREATE TABLE IF NOT EXISTS data_export_requests (
    id               BIGSERIAL    NOT NULL,
    -- Propriétaire — cascade delete si le compte est supprimé (RGPD Art. 17)
    user_id          BIGINT       NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'pending',
    -- SHA-256 du token de téléchargement brut — NULL tant que l'archive n'est pas prête
    token_hash       VARCHAR(64),
    -- Chemin absolu du fichier ZIP sur le stockage local
    file_path        VARCHAR(500),
    file_size_bytes  BIGINT,
    -- Renseigné uniquement si status = 'failed'
    error_message    TEXT,
    requested_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ,
    -- TTL du lien de téléchargement (24h après completed_at)
    expires_at       TIMESTAMPTZ,

    CONSTRAINT pk_data_export_requests PRIMARY KEY (id),
    CONSTRAINT fk_der_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_der_status CHECK (status IN ('pending', 'processing', 'ready', 'failed'))
);

-- Rate limit (1 export / 24h par utilisateur) : recherche de la dernière demande
CREATE INDEX IF NOT EXISTS idx_der_user_requested ON data_export_requests (user_id, requested_at DESC);

-- Résolution du token de téléchargement authentifié (jamais d'URL signée publique).
-- Index partiel : de nombreuses lignes ont token_hash NULL (pending/processing/failed).
CREATE UNIQUE INDEX IF NOT EXISTS idx_der_token_hash ON data_export_requests (token_hash)
    WHERE token_hash IS NOT NULL;

-- Purge planifiée des archives expirées (ExportCleanupScheduler)
CREATE INDEX IF NOT EXISTS idx_der_status_expires ON data_export_requests (status, expires_at)
    WHERE status = 'ready';

-- Ferme la fenêtre TOCTOU applicative : deux POST /api/account/export quasi simultanés
-- pourraient tous deux passer le check "pas de pending/processing" (DataExportService
-- #createPendingRequest) avant que l'un des deux inserts ne commit. Cette contrainte
-- partielle garantit au plus une ligne pending/processing par utilisateur au niveau BDD ;
-- le second INSERT concurrent lève DataIntegrityViolationException, traduite en 409 côté service.
CREATE UNIQUE INDEX IF NOT EXISTS idx_der_user_one_active ON data_export_requests (user_id)
    WHERE status IN ('pending', 'processing');

-- ================================================================
-- TABLE: account_deletion_requests
-- ================================================================
-- US02.2.4 — Suppression de compte (RGPD Art. 17).
-- One row per deletion *request* — kept (with cancelled_at set) even after cancellation, and
-- forever after the purge, for audit history (RGPD Art. 5.2 accountability).
CREATE TABLE IF NOT EXISTS account_deletion_requests (
    id                BIGSERIAL   NOT NULL,
    user_id           BIGINT      NOT NULL,
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Effective deletion date communicated to the user in the confirmation email
    -- (requested_at + grace period AT THE TIME of the request — a later admin change to
    -- ACCOUNT_DELETION_GRACE_DAYS never silently moves an already-communicated date).
    effective_at      TIMESTAMPTZ NOT NULL,
    -- How the deletion request itself was confirmed: 'password' (LOCAL accounts) or
    -- 'otp' (OIDC / no local password accounts).
    confirmed_via     VARCHAR(10) NOT NULL,
    -- SHA-256 hash of the raw cancellation token emailed to the user — the raw value is never
    -- persisted, same convention as every other token table in this schema.
    cancel_token_hash VARCHAR(64) NOT NULL,
    cancelled_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_account_deletion_requests PRIMARY KEY (id),
    CONSTRAINT fk_adr_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT chk_adr_confirmed_via CHECK (confirmed_via IN ('password', 'otp')),
    CONSTRAINT uq_adr_cancel_token_hash UNIQUE (cancel_token_hash)
);

CREATE INDEX IF NOT EXISTS idx_adr_user_id ON account_deletion_requests (user_id);

-- ================================================================
-- TABLE: account_deletion_otps
-- ================================================================
-- Email OTP confirmation for accounts without a local password (auth_mode OIDC / Google-only) —
-- US01.4.1's device-verification mechanism (device_verify_tokens), reused at the primitive
-- level (6-digit OTP, HMAC-SHA256 via pivot.auth.otp-secret, bounded attempts, short TTL). A
-- dedicated table rather than reusing device_verify_tokens directly: there is no
-- device/fingerprint concept for an account-deletion confirmation.
CREATE TABLE IF NOT EXISTS account_deletion_otps (
    id           BIGSERIAL   NOT NULL,
    user_id      BIGINT      NOT NULL,
    otp_hash     VARCHAR(64) NOT NULL,
    attempts     INT         NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_account_deletion_otps PRIMARY KEY (id),
    CONSTRAINT fk_ado_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ado_user_pending ON account_deletion_otps (user_id, expires_at)
    WHERE confirmed_at IS NULL;

-- ================================================================
-- TABLE: notifications
-- ================================================================
-- EN-NOTIF — Infrastructure notifications in-app. Une ligne par notification utilisateur.
-- tenant_id est dénormalisé depuis users.tenant_id au moment de la création
-- (NotificationService#create, jamais accepté depuis l'appelant) : permet un filtrage direct
-- (WHERE user_id = ? AND tenant_id = ?) sans jointure sur users à chaque lecture, et sert de
-- garde-fou défense-en-profondeur d'isolation tenant (voir CLAUDE.md « Règle transversale
-- sécurité — Isolation tenant »).
CREATE TABLE IF NOT EXISTS notifications (
    id          BIGSERIAL    NOT NULL,
    user_id     BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    -- Producteurs connus (voir NotificationType) : ROLE_CHANGED (US06.1.3) et
    -- ACCOUNT_DEACTIVATED (US06.1.4) sont câblés dès cet enabler. SENSITIVE_ACTION (US01.5.1)
    -- et UNKNOWN_DEVICE (US01.4.3a) existent déjà ici (type + libellés i18n) mais ne sont pas
    -- encore émis : leurs producteurs respectifs (core PR #154 / #151) ne sont pas fusionnés
    -- sur main et ne publient pas encore d'événement consommable — voir
    -- fr.pivot.notification.listener (package-info.java) pour le point d'intégration documenté.
    type        VARCHAR(30)  NOT NULL,
    title       VARCHAR(255) NOT NULL,
    body        TEXT         NOT NULL,
    -- NULL = non lue. Renseigné par PATCH /api/notifications/{id}/read ou .../read-all.
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user   FOREIGN KEY (user_id)   REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT chk_notifications_type CHECK (
        type IN ('ROLE_CHANGED', 'ACCOUNT_DEACTIVATED', 'SENSITIVE_ACTION', 'UNKNOWN_DEVICE')
    )
);

-- GET /api/notifications?page=&size= — pagination triée created_at DESC, scopée user+tenant.
CREATE INDEX IF NOT EXISTS idx_notifications_user_tenant_created
    ON notifications (user_id, tenant_id, created_at DESC);

-- GET /api/notifications/unread-count — index partiel (la plupart des lignes finissent lues).
CREATE INDEX IF NOT EXISTS idx_notifications_unread
    ON notifications (user_id, tenant_id)
    WHERE read_at IS NULL;

-- ================================================================
-- TABLE: teams
-- ================================================================
-- EN17.1 (volet team, pivot-core#171) — entité fondatrice partagée schéma public. Chaque
-- pivot-xxx-core référence public.teams(id) par FK cross-schéma (convention déjà documentée par
-- EN17.4) plutôt que de dupliquer localement la notion d'équipe. Pas d'API REST ni de logique
-- métier tant qu'aucune US ne la spécifie (voir fr.pivot.core.team.Team Javadoc, starter) —
-- uniquement le socle BDD + repositories dans ce ticket.
-- parent_team_id : auto-référence anticipée pour E15/EN15.3 (pivot-docs EPIC-equipes,
-- PR pivot-docs#151, encore phase-3/verrouillé) — une équipe est orpheline (racine, NULL) ou
-- rattachée à une équipe parente, structure en arbre. Ajoutée maintenant pour éviter une
-- migration de retrofit une fois E15 déverrouillé ; aucune logique de parcours/partage n'est
-- implémentée dans ce ticket, seule la colonne + sa FK existent.
-- slug / color / description : sous-ensemble découplé et inerte anticipé pour E15 (ADR-027,
-- pivot-docs#227, encore phase-3/verrouillé), même logique que parent_team_id (EN17.1,
-- pivot-core#171). slug = identifiant URL-safe unique par tenant (uq_teams_tenant_slug) ;
-- color / description = métadonnées d'affichage optionnelles. Colonnes découplées de la table
-- org_units (non créée, différée à E15) — aucune logique métier ici, seul le socle BDD existe.
CREATE TABLE IF NOT EXISTS teams (
    id              BIGSERIAL    NOT NULL,
    tenant_id       BIGINT       NOT NULL,
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL,
    color           VARCHAR(30),
    description     TEXT,
    parent_team_id  BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_teams PRIMARY KEY (id),
    CONSTRAINT uq_teams_tenant_name UNIQUE (tenant_id, name),
    CONSTRAINT uq_teams_tenant_slug UNIQUE (tenant_id, slug),
    CONSTRAINT fk_teams_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
    -- ON DELETE SET NULL (pas CASCADE) : supprimer une équipe parente ne doit pas supprimer en
    -- cascade tout son sous-arbre — ses enfants directs deviennent orphelins (promus racine),
    -- comportement par défaut le moins destructeur en l'absence de logique de partage implémentée
    -- (E15 verrouillé). À revisiter si EN15.1 spécifie explicitement un autre comportement.
    CONSTRAINT fk_teams_parent FOREIGN KEY (parent_team_id) REFERENCES teams (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_teams_tenant_id ON teams (tenant_id);
CREATE INDEX IF NOT EXISTS idx_teams_parent_team_id ON teams (parent_team_id);

-- ================================================================
-- TABLE: team_members
-- ================================================================
-- Association équipe <-> utilisateur (voir fr.pivot.core.team.TeamMember Javadoc).
-- role / updated_at : sous-ensemble découplé et inerte anticipé pour E15 (ADR-027, pivot-docs#227,
-- encore phase-3/verrouillé), même logique que parent_team_id (EN17.1, pivot-core#171). L'ADR-027
-- introduit une sémantique de rôle intra-équipe (RESPONSABLE|ADJOINT|MEMBRE) : l'appartenance
-- porte désormais un rôle modifiable, d'où l'ajout d'updated_at (l'appartenance peut évoluer, plus
-- seulement se créer/supprimer). CHECK côté BDD borne les valeurs ; aucune logique métier de rôle
-- (permission, promotion...) n'est implémentée ici — seul le socle BDD existe.
CREATE TABLE IF NOT EXISTS team_members (
    id          BIGSERIAL   NOT NULL,
    team_id     BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    role        VARCHAR(20) NOT NULL DEFAULT 'MEMBRE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_team_members PRIMARY KEY (id),
    CONSTRAINT uq_team_members_team_user UNIQUE (team_id, user_id),
    CONSTRAINT chk_tm_role CHECK (role IN ('RESPONSABLE', 'ADJOINT', 'MEMBRE')),
    CONSTRAINT fk_tm_team FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
    CONSTRAINT fk_tm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tm_team_id ON team_members (team_id);
CREATE INDEX IF NOT EXISTS idx_tm_user_id ON team_members (user_id);

-- ================================================================
-- SEED DATA: initial tenant + feature flags
-- ================================================================

-- Default PIVOT SaaS tenant
INSERT INTO tenants (slug, name, plan, auth_mode, is_active)
VALUES ('pivot-saas', 'PIVOT SaaS', 'SAAS', 'SAAS', true)
ON CONFLICT (slug) DO NOTHING;

-- Platform feature flags
INSERT INTO feature_flags (flag_key, enabled, value, type, description)
VALUES
    ('MFA_NEW_DEVICE_OTP',              false, 'false',   'bool',
     'Email OTP requis lors de la connexion depuis un nouvel appareil'),
    ('MFA_30DAY_REAUTH_OTP',            false, 'false',   'bool',
     'Email OTP requis si dernier appareil de confiance date de plus de 30 jours'),
    ('SESSION_REFRESH_THRESHOLD',       true,  '0.15',    'float',
     'Ratio TTL restant déclenchant le renouvellement automatique de session (ex: 0.15 = derniers 15%)'),
    ('SESSION_ROTATION_GRACE_SECONDS',  true,  '30',      'int',
     'Fenêtre de grâce (s) pendant laquelle l''ancien token reste valide après rotation (requêtes concurrentes en vol)'),
    ('SESSION_TTL_SECONDS',             true,  '86400',   'int',
     'Durée de vie d''une session standard en secondes (86400 = 24h)'),
    ('SESSION_TTL_REMEMBER_ME_SECONDS', true,  '2592000', 'int',
     'Durée de vie d''une session «Se souvenir de moi» en secondes (2592000 = 30 jours)'),
    ('MAX_SESSIONS_PER_USER',           true,  '5',       'int',
     'Nombre maximum de sessions actives simultanées par utilisateur. La plus ancienne est révoquée si la limite est atteinte.'),
    ('PASSWORD_RESET_TTL_MINUTES',      true,  '15',      'int',
     'Durée de validité du lien de réinitialisation de mot de passe en minutes.'),
    ('DEVICE_VERIFY_TTL_MINUTES',       true,  '15',      'int',
     'Durée de validité du code OTP de vérification d''un nouvel appareil en minutes.'),
    ('DEVICE_TTL_DAYS',                 true,  '90',      'int',
     'Sliding TTL en jours avant qu''un appareil de confiance doive re-vérifier.'),
    ('ACCOUNT_DELETION_GRACE_DAYS',      true, '30', 'int',
     'Délai de grâce (jours) avant purge effective (anonymisation) d''un compte en cours de suppression (RGPD Art. 17).'),
    ('ACCOUNT_DELETION_OTP_TTL_MINUTES', true, '10', 'int',
     'Durée de validité (minutes) du code OTP de confirmation de suppression de compte (comptes sans mot de passe local).'),
    ('SUSPICIOUS_LOGIN_OTP_TTL_MINUTES', true, '60', 'int',
     'Durée de validité (minutes) du lien « Pas moi » envoyé lors d''une alerte de connexion suspecte (US01.4.3a).')
ON CONFLICT (flag_key) DO NOTHING;

-- Labels for admin UI
UPDATE feature_flags SET label = 'Seuil renouvellement session'        WHERE flag_key = 'SESSION_REFRESH_THRESHOLD';
UPDATE feature_flags SET label = 'Fenêtre de grâce rotation (s)'       WHERE flag_key = 'SESSION_ROTATION_GRACE_SECONDS';
UPDATE feature_flags SET label = 'TTL session standard (s)'            WHERE flag_key = 'SESSION_TTL_SECONDS';
UPDATE feature_flags SET label = 'TTL session longue durée (s)'        WHERE flag_key = 'SESSION_TTL_REMEMBER_ME_SECONDS';
UPDATE feature_flags SET label = 'Sessions max par utilisateur'        WHERE flag_key = 'MAX_SESSIONS_PER_USER';
UPDATE feature_flags SET label = 'TTL lien reset mot de passe (min)'   WHERE flag_key = 'PASSWORD_RESET_TTL_MINUTES';
UPDATE feature_flags SET label = 'TTL OTP vérification appareil (min)' WHERE flag_key = 'DEVICE_VERIFY_TTL_MINUTES';
UPDATE feature_flags SET label = 'Durée de confiance appareil (jours)' WHERE flag_key = 'DEVICE_TTL_DAYS';
UPDATE feature_flags SET label = 'Délai de grâce suppression compte (jours)' WHERE flag_key = 'ACCOUNT_DELETION_GRACE_DAYS';
UPDATE feature_flags SET label = 'TTL OTP suppression compte (min)'         WHERE flag_key = 'ACCOUNT_DELETION_OTP_TTL_MINUTES';
UPDATE feature_flags SET label = 'TTL lien « Pas moi » alerte connexion (min)' WHERE flag_key = 'SUSPICIOUS_LOGIN_OTP_TTL_MINUTES';
