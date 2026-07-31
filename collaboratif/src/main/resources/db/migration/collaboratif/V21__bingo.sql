-- V21: Bingo des reunions (US47.1.1, E47/F47.1) -- room-scoped real-time mini-game. Declines the
-- planning-poker (agilite) room + Redis access-grant pattern (EN09.1) inside the collaboratif
-- module (fr.pivot.collaboratif.bingo) -- no inter-module dependency (ADR-006). Additive, never
-- touching V1..V20 (renumbered from V17 -- the MeetOps module (US12.x) claimed V17..V20 for its
-- own migrations in a parallel branch; both branches picked the next-free version independently
-- since neither existed on main when the other started, discovered and resolved on merge).

-- bingo_rooms: one row per game. code is the 6-character invite code (InviteCodeGenerator
-- alphabet, decliner of agilite's own generator). creator_user_id/tenant_id are nullable because
-- the creator is always authenticated for room creation (AC-47.1.1-01) but kept nullable for
-- schema symmetry with the anonymous-join model used elsewhere in this table (no FK dependency
-- on the join path). winner_participant_id references bingo_grids.id (the grid IS the stable,
-- non-enumerable per-participant identifier broadcast over the wire as "participantId" -- see
-- BingoGrid's Javadoc) once a bingo is detected; winning_line_kind/index record which of the 12
-- combinations completed first.
CREATE TABLE IF NOT EXISTS collaboratif.bingo_rooms (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    code                  CHAR(6)      NOT NULL UNIQUE,
    name                  VARCHAR(80)  NOT NULL,
    creator_user_id       BIGINT       REFERENCES public.users(id),
    tenant_id             BIGINT       REFERENCES public.tenants(id),
    status                VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'FINISHED')),
    max_players           INTEGER      NOT NULL DEFAULT 50,
    winner_participant_id UUID,
    winning_line_kind     VARCHAR(20),
    winning_line_index    SMALLINT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bingo_rooms_code ON collaboratif.bingo_rooms(code);

-- bingo_phrases: the default phrase bank shared by every room (US47.1.1 scope -- no per-room
-- custom bank, see the US's "hors perimetre"). Invariant: >= 25 rows, checked at room creation.
CREATE TABLE IF NOT EXISTS collaboratif.bingo_phrases (
    id     UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    phrase VARCHAR(200) NOT NULL
);

-- bingo_grids: one row per participant per room -- the room-scoped, non-enumerable identifier
-- ("participantId" on the wire) is this row's own id, never the access token or its hash.
-- participant_key is the hex SHA-256 digest of the participant's opaque accessToken (SEC-03) --
-- the raw token itself is never persisted. role distinguishes a PLAYER (has a grid) from a
-- SPECTATOR admitted past max_players (AC-47.1.1-13) -- spectators never get a row here at all
-- (BingoRoomService only inserts a grid for PLAYER joins), so absence-of-row is how the mark path
-- (BingoMarkService) recognizes a spectator's SEND attempt (AC-47.1.1-14). The role column is
-- still persisted (rather than always PLAYER) purely for read-side clarity/future-proofing.
CREATE TABLE IF NOT EXISTS collaboratif.bingo_grids (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    room_id         UUID        NOT NULL REFERENCES collaboratif.bingo_rooms(id) ON DELETE CASCADE,
    participant_key CHAR(64)    NOT NULL,
    display_name    VARCHAR(30) NOT NULL,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('PLAYER', 'SPECTATOR')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (room_id, participant_key)
);
CREATE INDEX IF NOT EXISTS idx_bingo_grids_room_id ON collaboratif.bingo_grids(room_id);

-- bingo_grid_cells: the 25 cells of a grid. phrase_text is a denormalized snapshot of the phrase
-- at generation time (kept alongside the phrase_id FK for traceability) -- deliberate: a
-- reconnecting participant (AC-47.1.1-05) must always see the exact same grid it was dealt, even
-- if the shared phrase bank is edited later (out of this US's scope, but the schema should not
-- silently break that invariant the day it lands).
CREATE TABLE IF NOT EXISTS collaboratif.bingo_grid_cells (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    grid_id     UUID         NOT NULL REFERENCES collaboratif.bingo_grids(id) ON DELETE CASCADE,
    cell_index  SMALLINT     NOT NULL CHECK (cell_index BETWEEN 0 AND 24),
    phrase_id   UUID         NOT NULL REFERENCES collaboratif.bingo_phrases(id),
    phrase_text VARCHAR(200) NOT NULL,
    marked      BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (grid_id, cell_index)
);
CREATE INDEX IF NOT EXISTS idx_bingo_grid_cells_grid_id ON collaboratif.bingo_grid_cells(grid_id);

-- Seed the default phrase bank (>= 25 FR meeting phrases) -- idempotent, only runs once (this
-- file itself never re-runs once applied, but the WHERE NOT EXISTS guard keeps a manual re-apply
-- safe too, consistent with this schema's IF NOT EXISTS discipline elsewhere).
INSERT INTO collaboratif.bingo_phrases (phrase)
SELECT phrase FROM (VALUES
    ('On fait un point offline ?'),
    ('Ca manque de sponsor'),
    ('Tu peux partager ton ecran ?'),
    ('On est aligne ?'),
    ('Je te tiens au courant'),
    ('On n''a pas le budget pour ca'),
    ('C''est dans le backlog'),
    ('On va prendre ca en asynchrone'),
    ('Qui pilote ce sujet ?'),
    ('On reboucle la semaine prochaine'),
    ('Je n''ai pas eu le temps de regarder'),
    ('On est sur la meme longueur d''onde ?'),
    ('C''est un detail d''implementation'),
    ('On va faire un MVP'),
    ('Il faut qu''on challenge ca'),
    ('Je pense qu''on doit prendre du recul'),
    ('On va scaler ca plus tard'),
    ('C''est plus complique que ca en a l''air'),
    ('On a un souci de perimetre'),
    ('Est-ce que tout le monde m''entend ?'),
    ('Je suis en retard, desole'),
    ('On peut prendre ca offline avec les concernes'),
    ('Il nous faut un owner sur ce sujet'),
    ('On revient dessus au prochain point'),
    ('Ca depend de la roadmap'),
    ('On n''a pas assez de visibilite'),
    ('Il faut qu''on documente ca'),
    ('C''est une dette technique connue'),
    ('On est en train de converger'),
    ('Je propose qu''on prenne 5 minutes pour recadrer')
) AS seed(phrase)
WHERE NOT EXISTS (SELECT 1 FROM collaboratif.bingo_phrases);
