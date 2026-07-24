-- V16: Module Session live (E19, PR2/2) — QUIZ activity tables (US19.3.1), building on the shared
-- session/participant tables from V12. Additive, never touching V1..V15. The question bank itself
-- lives in the session's JSONB config (text/options/correctIndices/durationSeconds per question);
-- only live progression and graded answers are persisted here.

-- session_quiz_state: one row per QUIZ session tracking the live progression. current_question_index
-- is -1 before the first question. question_started_at is the server-authoritative clock the
-- backend uses to reject late answers and rank speed bonuses; question_ended gates answer intake and
-- correct-answer reveal.
CREATE TABLE IF NOT EXISTS collaboratif.session_quiz_state (
    session_id             UUID        NOT NULL PRIMARY KEY REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    current_question_index INTEGER     NOT NULL DEFAULT -1,
    question_started_at    TIMESTAMPTZ,
    question_ended         BOOLEAN     NOT NULL DEFAULT TRUE
);

-- session_quiz_answer: one row per (session, participant, question_index), never overwritten. The
-- graded outcome (correct) and points_awarded (base + submission-rank speed bonus) are frozen at
-- submission time, so the leaderboard is a plain sum with no re-grading. selected_indices is a JSONB
-- array of chosen option indices.
CREATE TABLE IF NOT EXISTS collaboratif.session_quiz_answer (
    id                UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id        UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    participant_id    UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    question_index    INTEGER     NOT NULL,
    selected_indices  JSONB       NOT NULL,
    correct           BOOLEAN     NOT NULL,
    points_awarded    INTEGER     NOT NULL,
    submitted_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_quiz_answer_participant_question
    ON collaboratif.session_quiz_answer(session_id, participant_id, question_index);
CREATE INDEX IF NOT EXISTS idx_session_quiz_answer_question
    ON collaboratif.session_quiz_answer(session_id, question_index);
