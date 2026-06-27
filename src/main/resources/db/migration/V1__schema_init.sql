-- pivot-platform schema v1 — consolidated DDL
-- Source of truth for fresh installs

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
    -- SAAS | ENTERPRISE | HYBRID — drives available auth methods
    auth_mode   VARCHAR(20)  NOT NULL DEFAULT 'SAAS',
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_tenants PRIMARY KEY (id),
    CONSTRAINT uq_tenants_slug UNIQUE (slug),
    CONSTRAINT chk_tenants_plan CHECK (plan IN ('SAAS', 'ENTERPRISE', 'TRIAL')),
    CONSTRAINT chk_tenants_auth_mode CHECK (auth_mode IN ('SAAS', 'ENTERPRISE', 'HYBRID'))
);

CREATE INDEX IF NOT EXISTS idx_tenants_slug ON tenants (slug);

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
    -- Preferred language (i18n). Inherited from browser on first login.
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
    -- Soft delete RGPD Art. 17
    deleted_at                  TIMESTAMPTZ,
    scheduled_deletion_at       TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_tenant_email ON users (tenant_id, email);
CREATE INDEX IF NOT EXISTS idx_users_google_id ON users (google_id);
CREATE INDEX IF NOT EXISTS idx_users_deleted_at ON users (deleted_at);
CREATE INDEX IF NOT EXISTS idx_users_oidc_subject ON users (oidc_subject);

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
     'Sliding TTL en jours avant qu''un appareil de confiance doive re-vérifier.')
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
