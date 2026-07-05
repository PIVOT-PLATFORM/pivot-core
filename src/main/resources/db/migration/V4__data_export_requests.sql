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
