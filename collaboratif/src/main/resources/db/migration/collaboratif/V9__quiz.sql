-- V9: quiz_session + quiz_question + quiz_choice + quiz_answer — facilitator-driven MCQ quiz
-- activity over a board (Quiz feature, Kahoot/Klaxoon-style workshop facilitation).
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and the
-- headers of V2__whiteboard_parity.sql / V3__card_connection.sql / V4__vote.sql for the precedent
-- this follows): V2..V8 have already been applied against the real, persistent recette-managed
-- Cloud SQL instance (pivot-project-501905, Cloud Run service pivot-collaboratif-core) by the
-- continuous-deploy pipeline live since 2026-07-14. Flyway has therefore already recorded
-- checksummed V1..V8 rows in that database's flyway_schema_history — editing any of them in place
-- would invalidate its checksum and break Flyway validation on the next deploy. V9 is therefore
-- additive, following the same schema/table conventions established by V1..V8 (see V4__vote.sql
-- in particular, the closest structural precedent: a facilitation session scoped to a board with
-- a single-ACTIVE-per-board invariant).
--
-- MVP scope (see QUIZ-ACTIVITY-DESIGN.md §1.2/§3.2/§3.8): "Quiz QCM animé par le facilitateur".
-- The facilitator composes N questions upfront (quiz:start), then drives progression question by
-- question (quiz:next/quiz:reveal/quiz:stop). Multi-correct choices, speed bonus and per-question
-- timers are pre-provisioned in this schema (quiz_choice.correct is not constrained to a single
-- TRUE per question; quiz_answer.answered_at; quiz_session.timer_ends_at) but are NOT wired by the
-- MVP application logic — phase 2.

-- quiz_session: one quiz session over a board. At most one ACTIVE session per board at a time —
-- enforced both in application code (QuizActionService, pre-insert check) and at the DB level by
-- the partial unique index below, so a concurrent double-start cannot create two ACTIVE sessions
-- (same pattern as vote_session, see V4__vote.sql). FK to board ON DELETE CASCADE: deleting a
-- board removes its quiz sessions. FK tenant_id -> public.tenants(id): no ON DELETE CASCADE
-- (public.tenants are soft-deleted / never hard-deleted), same convention as vote_session.
--
-- current_question_index / current_state: track the question currently being played. Both are
-- nullable — null until the facilitator starts the quiz (current_question_index) / before any
-- question has been opened (current_state). The MVP drives current_state through OPEN/REVEALED
-- only; PENDING/CLOSED remain available in the application enum for future per-question tracking
-- but are not set by the MVP service layer on quiz_session.
--
-- timer_ends_at: pre-provisioned for the per-question auto-advancing timer (phase 2, not wired by
-- the MVP, which is driven manually by the facilitator).
CREATE TABLE IF NOT EXISTS collaboratif.quiz_session (
    id                     UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    board_id               UUID        NOT NULL REFERENCES collaboratif.board(id) ON DELETE CASCADE,
    tenant_id              BIGINT      NOT NULL REFERENCES public.tenants(id),
    status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    current_question_index INTEGER,
    current_state          VARCHAR(20),
    timer_ends_at          TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at              TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_quiz_session_board  ON collaboratif.quiz_session(board_id);
CREATE INDEX IF NOT EXISTS idx_quiz_session_tenant ON collaboratif.quiz_session(tenant_id);

-- At most one ACTIVE session per board (partial unique index) — the DB-level backstop for the
-- application's single-active-session invariant, same pattern as uq_vote_session_active_per_board.
CREATE UNIQUE INDEX IF NOT EXISTS uq_quiz_session_active_per_board
    ON collaboratif.quiz_session(board_id) WHERE status = 'ACTIVE';

-- quiz_question: one MCQ question belonging to a quiz session, ordered by position (0-based).
-- FK session_id ON DELETE CASCADE: closing/deleting the session removes its questions.
-- time_limit_seconds is pre-provisioned for the per-question timer (phase 2, not wired by MVP).
CREATE TABLE IF NOT EXISTS collaboratif.quiz_question (
    id                 UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id         UUID        NOT NULL REFERENCES collaboratif.quiz_session(id) ON DELETE CASCADE,
    position           INTEGER     NOT NULL,
    text               VARCHAR(500) NOT NULL,
    time_limit_seconds INTEGER
);
CREATE INDEX IF NOT EXISTS idx_quiz_question_session ON collaboratif.quiz_question(session_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_quiz_question_pos ON collaboratif.quiz_question(session_id, position);

-- quiz_choice: one answer choice belonging to a question, ordered by position (0-based).
-- FK question_id ON DELETE CASCADE: deleting the question removes its choices.
-- correct: the sensitive field — never exposed via any DTO before the owning question is
-- REVEALED (see ChoiceResponse vs ChoiceRevealResponse, application-layer masking, lot C1/B1).
-- The MVP UI enforces a single-correct radio selection, but the schema does not constrain the
-- count of TRUE rows per question — multi-correct is pre-provisioned for phase 2.
CREATE TABLE IF NOT EXISTS collaboratif.quiz_choice (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    question_id UUID        NOT NULL REFERENCES collaboratif.quiz_question(id) ON DELETE CASCADE,
    position    INTEGER     NOT NULL,
    text        VARCHAR(300) NOT NULL,
    correct     BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_quiz_choice_question ON collaboratif.quiz_choice(question_id);

-- quiz_answer: a single participant's answer to a question within a session. Unlike vote (which
-- deliberately stacks, see V4__vote.sql), the quiz answer is unique per (session, question, user)
-- — a participant may change their mind while the question is OPEN, which the application layer
-- implements as an upsert against this constraint (QuizActionService.handleAnswer), not as
-- multiple stacked rows. FK session_id/question_id/choice_id all ON DELETE CASCADE: deleting the
-- session/question/choice removes associated answers. user_id -> public.users(id): no ON DELETE
-- CASCADE (users are soft-deleted), same convention as vote.user_id.
-- answered_at is pre-provisioned for the speed-bonus scoring (phase 2, not wired by MVP — the
-- score is computed purely from correctness at reveal time).
CREATE TABLE IF NOT EXISTS collaboratif.quiz_answer (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES collaboratif.quiz_session(id) ON DELETE CASCADE,
    question_id UUID        NOT NULL REFERENCES collaboratif.quiz_question(id) ON DELETE CASCADE,
    choice_id   UUID        NOT NULL REFERENCES collaboratif.quiz_choice(id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL REFERENCES public.users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_quiz_answer_once
    ON collaboratif.quiz_answer(session_id, question_id, user_id);
CREATE INDEX IF NOT EXISTS idx_quiz_answer_session ON collaboratif.quiz_answer(session_id);
