-- US02.2.4 — Suppression de compte (RGPD Art. 17).
--
-- users.deleted_at / users.scheduled_deletion_at already exist since V1 (columns reserved for
-- this exact feature — see their comment there). Reused here as:
--   deleted_at            -> set IMMEDIATELY when the deletion is requested. Marks the account
--                            "PENDING_DELETION": invisible to admin reads
--                            (UserSpecifications.notDeleted()) and unresolvable at login
--                            (every *AndDeletedAtIsNull lookup in UserRepository already used by
--                            SessionService / GoogleAuthService / OidcAuthService) — no new code
--                            needed there for the "401/403 on any login attempt" AC.
--   scheduled_deletion_at -> effective purge date communicated to the user (grace period
--                            deadline), consumed by AccountDeletionScheduler.
-- anonymized_at marks that the scheduled purge has actually anonymized the row — distinguishes
-- "pending, still within grace period" from "purged" without parsing users.email.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS anonymized_at TIMESTAMPTZ;

-- Feeds AccountDeletionScheduler.anonymizeDueAccounts() — accounts whose grace period elapsed
-- and have not been anonymized yet.
CREATE INDEX IF NOT EXISTS idx_users_scheduled_deletion ON users (scheduled_deletion_at)
    WHERE deleted_at IS NOT NULL AND anonymized_at IS NULL;

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

INSERT INTO feature_flags (flag_key, enabled, value, type, description, label)
VALUES
    ('ACCOUNT_DELETION_GRACE_DAYS', true, '30', 'int',
     'Délai de grâce (jours) avant purge effective (anonymisation) d''un compte en cours de suppression (RGPD Art. 17).',
     'Délai de grâce suppression compte (jours)'),
    ('ACCOUNT_DELETION_OTP_TTL_MINUTES', true, '10', 'int',
     'Durée de validité (minutes) du code OTP de confirmation de suppression de compte (comptes sans mot de passe local).',
     'TTL OTP suppression compte (min)')
ON CONFLICT (flag_key) DO NOTHING;
