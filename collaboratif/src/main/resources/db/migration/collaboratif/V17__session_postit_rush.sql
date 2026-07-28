-- V17: Module Session live (E19, E47/F47.2) — POST-IT RUSH mini-game tables (US47.2.1), building
-- on the shared session/participant tables from V12. Additive, never touching V1..V16.

-- session_participant.role: US47.2.1 introduces the first non-PLAYER role (SPECTATOR, assigned
-- only past a POSTIT_RUSH room's hard capacity). Every row created before this migration, and
-- every row of every other session type, defaults to PLAYER.
ALTER TABLE collaboratif.session_participant
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'PLAYER';

-- session_postit_rush_round: one row per round of a POSTIT_RUSH session. duration_seconds/
-- started_at are the server-authoritative clock (client countdown is display-only); ended_at is
-- null while the round is active. next_spawn_at is the scheduler's own bookkeeping for when to
-- generate the next post-it (never exposed to clients — the spawn schedule is never
-- pre-disclosed). leaderboard_broadcast_at/leaderboard_dirty implement the 500ms broadcast
-- throttle (widened above softCap, see PostitRushScheduler).
CREATE TABLE IF NOT EXISTS collaboratif.session_postit_rush_round (
    id                        UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id                UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    round_number              INTEGER     NOT NULL,
    duration_seconds          INTEGER     NOT NULL,
    started_at                TIMESTAMPTZ NOT NULL,
    ended_at                  TIMESTAMPTZ,
    next_spawn_at             TIMESTAMPTZ,
    leaderboard_broadcast_at  TIMESTAMPTZ,
    leaderboard_dirty         BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_session_postit_rush_round_session
    ON collaboratif.session_postit_rush_round(session_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_postit_rush_round_session_number
    ON collaboratif.session_postit_rush_round(session_id, round_number);

-- session_postit_rush_spawn: one row per server-generated post-it. x/y are percentage coordinates
-- (0-100) on the shared board, so any client viewport can render it. claimed_by/claimed_at are set
-- atomically with the first winning click (SessionPostitRushSpawnRepository.claim, an optimistic
-- UPDATE ... WHERE claimed_by IS NULL AND expired = FALSE); expired is set by the scheduler once
-- spawned_at + lifespan_ms elapses unclaimed.
CREATE TABLE IF NOT EXISTS collaboratif.session_postit_rush_spawn (
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    round_id      UUID         NOT NULL REFERENCES collaboratif.session_postit_rush_round(id) ON DELETE CASCADE,
    x             NUMERIC(5,2) NOT NULL,
    y             NUMERIC(5,2) NOT NULL,
    color_key     VARCHAR(20)  NOT NULL,
    spawned_at    TIMESTAMPTZ  NOT NULL,
    lifespan_ms   INTEGER      NOT NULL,
    claimed_by    UUID         REFERENCES collaboratif.session_participant(id) ON DELETE SET NULL,
    claimed_at    TIMESTAMPTZ,
    expired       BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_session_postit_rush_spawn_round
    ON collaboratif.session_postit_rush_spawn(round_id);
CREATE INDEX IF NOT EXISTS idx_session_postit_rush_spawn_live
    ON collaboratif.session_postit_rush_spawn(round_id)
    WHERE claimed_by IS NULL AND expired = FALSE;

-- session_postit_rush_participant_round: per-round mutable score/combo state — reset to zero for
-- each new round (rounds are independent, results reported per round). score_reached_at tracks
-- when the participant's score last changed, used as the leaderboard's earliest-to-reach tie-break.
CREATE TABLE IF NOT EXISTS collaboratif.session_postit_rush_participant_round (
    round_id         UUID        NOT NULL REFERENCES collaboratif.session_postit_rush_round(id) ON DELETE CASCADE,
    participant_id   UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    score            INTEGER     NOT NULL DEFAULT 0,
    current_combo    INTEGER     NOT NULL DEFAULT 0,
    best_combo       INTEGER     NOT NULL DEFAULT 0,
    hits             INTEGER     NOT NULL DEFAULT 0,
    score_reached_at TIMESTAMPTZ,
    PRIMARY KEY (round_id, participant_id)
);
