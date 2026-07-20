-- V8: backfill collaboratif.frame on databases that applied V1 before the frame table was
-- folded into V1's (mutable, pre-BETA) body.
--
-- Why this migration exists: the `collaboratif.frame` table is declared in V1__schema_init.sql
-- (EN08 Frames). Under the "V1 mutable avant la BETA" convention it was added to V1's body after
-- the persistent recette-managed Cloud SQL instance had already applied V1. Flyway keys a
-- migration by version, not content: it never re-runs an applied V1, so the table was never
-- created there — and the checksum drift that would otherwise surface this was silently absorbed
-- by the deploy pipeline's `flyway repair` (which realigns the recorded checksum WITHOUT re-running
-- the migration). Result on recette: `ERROR: relation "collaboratif.frame" does not exist` on every
-- board load and Klaxoon import (both query the frame table — WhiteboardImportService offset scan
-- and the board:state hydration).
--
-- Same additive rationale as V7's header: editing V1 in place cannot reach an already-migrated
-- persistent DB, so the fix is a forward migration. `IF NOT EXISTS` makes it a no-op on fresh
-- databases (where V1 already created the table) and a create on legacy ones — the definition is a
-- byte-for-byte copy of V1's, so both paths converge on the same schema.

CREATE TABLE IF NOT EXISTS collaboratif.frame (
    id          UUID             NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    board_id    UUID             NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    tenant_id   BIGINT           NOT NULL REFERENCES public.tenants(id),
    pos_x       DOUBLE PRECISION NOT NULL DEFAULT 0,
    pos_y       DOUBLE PRECISION NOT NULL DEFAULT 0,
    width       DOUBLE PRECISION NOT NULL DEFAULT 400,
    height      DOUBLE PRECISION NOT NULL DEFAULT 300,
    title       VARCHAR(200)     NOT NULL DEFAULT '',
    color       VARCHAR(20)      NOT NULL DEFAULT '#94A3B8',
    active      BOOLEAN          NOT NULL DEFAULT false,
    layer       INTEGER          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ      NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_frame_board ON collaboratif.frame(board_id, layer ASC, created_at ASC);
