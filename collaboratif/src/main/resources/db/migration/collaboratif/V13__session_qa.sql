-- V13: Module Session live (E19, PR2/2) — Q&A activity tables (US19.3.5), building on the shared
-- session/participant tables from V12. Additive, never touching V1..V12 (see V12's header for the
-- Flyway convention this follows).

-- session_qa_question: one row per participant-submitted question. participant_id always records
-- the author (ownership/moderation) even when anonymous=true — anonymity only withholds the
-- author's display name from other participants at read time, it does not detach the row from its
-- author server-side. text is stored verbatim; escaping is a render-time (frontend) concern.
CREATE TABLE IF NOT EXISTS collaboratif.session_qa_question (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    participant_id  UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    text            VARCHAR(500) NOT NULL,
    anonymous       BOOLEAN     NOT NULL DEFAULT FALSE,
    answered        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_session_qa_question_session ON collaboratif.session_qa_question(session_id);

-- session_qa_upvote: at most one upvote per (question, participant) — the unique index is what
-- turns a repeated upvote into a 409 (US19.3.5) rather than a silently inflated tally.
CREATE TABLE IF NOT EXISTS collaboratif.session_qa_upvote (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    question_id     UUID        NOT NULL REFERENCES collaboratif.session_qa_question(id) ON DELETE CASCADE,
    participant_id  UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_qa_upvote_participant
    ON collaboratif.session_qa_upvote(question_id, participant_id);
