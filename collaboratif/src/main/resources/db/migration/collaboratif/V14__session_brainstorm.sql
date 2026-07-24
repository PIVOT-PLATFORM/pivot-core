-- V14: Module Session live (E19, PR2/2) — BRAINSTORM activity table (US19.3.4), building on the
-- shared session/participant tables from V12. Additive, never touching V1..V13.

-- session_brainstorm_card: one row per post-it. participant_id is the author — the only participant
-- allowed to edit/delete the card (enforced in BrainstormActivityService, a non-author edit is a
-- 403). text is stored verbatim (escaping is a render-time concern). color is constrained to the
-- fixed BrainstormCardColor palette at the application layer. category is a facilitator-assigned
-- free-text grouping label, NULL until grouped.
CREATE TABLE IF NOT EXISTS collaboratif.session_brainstorm_card (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    participant_id  UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    text            VARCHAR(280) NOT NULL,
    color           VARCHAR(20) NOT NULL,
    category        VARCHAR(80),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_session_brainstorm_card_session
    ON collaboratif.session_brainstorm_card(session_id);
