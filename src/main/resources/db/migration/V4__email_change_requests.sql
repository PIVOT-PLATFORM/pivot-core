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
