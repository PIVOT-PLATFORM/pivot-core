-- V15: Module Session live (E19, PR2/2) — VOTE activity tables (US19.3.6), building on the shared
-- session/participant tables from V12. Additive, never touching V1..V14.

-- session_vote_ballot: one ballot per (session, participant), never overwritten — a second
-- submission is a 409 (a structured decision is an auditable one-shot record, unlike a re-votable
-- POLL). payload is a JSONB object whose shape depends on the vote type: {"value": 0..5} for
-- FIST_TO_FIVE, {"allocations": {"0": n, ...}} for WEIGHTED.
CREATE TABLE IF NOT EXISTS collaboratif.session_vote_ballot (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    participant_id  UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    payload         JSONB       NOT NULL,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_vote_ballot_participant
    ON collaboratif.session_vote_ballot(session_id, participant_id);

-- session_vote_state: one row per VOTE session, tracks whether the facilitator has closed the vote
-- (US19.3.6) — absent row = still open (default), tallies are only revealed once closed = true.
CREATE TABLE IF NOT EXISTS collaboratif.session_vote_state (
    session_id  UUID    NOT NULL PRIMARY KEY REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    closed      BOOLEAN NOT NULL DEFAULT FALSE
);
