-- V12: Module Session live (E19, PR1/2) — session, activity, participant, response socle plus
-- POLL and WORDCLOUD activity tables. QUIZ/BRAINSTORM/QA/VOTE and their response tables follow in
-- a later migration (PR2/2), building on the shared session/participant/response tables here.
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and the
-- headers of V2..V11 for the precedent this follows): V2..V11 have already been applied against
-- the real, persistent recette-managed Cloud SQL instance by the continuous-deploy pipeline.
-- V12 is therefore additive, never touching V1..V11.

-- session: a live interactive session (QUIZ/POLL/WORDCLOUD/BRAINSTORM/QA/VOTE), joined via a
-- short 6-character code. team_id is optional (a session may be created for individual use,
-- unlike whiteboard boards). config is a JSONB blob whose shape depends on type — see
-- SessionType-specific tables (session_poll_option, ...) for the normalized, queryable subset of
-- that config once an activity is configured.
CREATE TABLE IF NOT EXISTS collaboratif.session (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    team_id     BIGINT      REFERENCES public.teams(id),
    title       VARCHAR(120) NOT NULL,
    type        VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    join_code   VARCHAR(6)  NOT NULL,
    config      JSONB,
    created_by  BIGINT      NOT NULL REFERENCES public.users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at  TIMESTAMPTZ,
    ended_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_session_tenant ON collaboratif.session(tenant_id);
CREATE INDEX IF NOT EXISTS idx_session_team ON collaboratif.session(team_id);
-- Join codes only need to be unique among sessions not yet COMPLETED (US19.1.1) — a completed
-- session's code may be reused by a later session.
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_join_code_active
    ON collaboratif.session(tenant_id, join_code) WHERE status <> 'COMPLETED';

-- session_activity: a distinct entity even though this socle only supports a 1:1 mapping to its
-- owning session (type is fixed at session creation, no multi-activity sequence within one
-- session yet) — kept separate at the schema level to absorb a future multi-activity evolution
-- without a breaking migration (US19.1.1 §Notes d'implémentation).
CREATE TABLE IF NOT EXISTS collaboratif.session_activity (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id  UUID        NOT NULL UNIQUE REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    type        VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- session_participant: user_id NULL = anonymous ROLE_GUEST participant (US19.2.1), identified
-- instead by a sealed, session-scoped guest_token. display_name is caller-supplied per session,
-- may differ from the account's own profile name. last_heartbeat_at drives guest-token expiry
-- (only meaningful for guests; NULL/ignored for authenticated participants).
CREATE TABLE IF NOT EXISTS collaboratif.session_participant (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id          UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    user_id             BIGINT,
    guest_token         VARCHAR(100),
    display_name        VARCHAR(40) NOT NULL,
    joined_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_heartbeat_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_session_participant_session ON collaboratif.session_participant(session_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_participant_guest_token
    ON collaboratif.session_participant(guest_token) WHERE guest_token IS NOT NULL;

-- session_response: shared, minimal, polymorphic-base columns only (id/session_id/participant_id/
-- created_at) — this socle poses no type-specific columns; PR2/2 extends this for QUIZ/
-- BRAINSTORM/QA/VOTE response shapes (US19.1.1 §Notes d'implémentation). POLL and WORDCLOUD (this
-- PR) use their own dedicated tables below rather than this generic one, since both have a
-- natural normalized shape (option votes, word frequency) that a single polymorphic response row
-- would not represent well.
CREATE TABLE IF NOT EXISTS collaboratif.session_response (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    participant_id  UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_session_response_session ON collaboratif.session_response(session_id);

-- session_poll_option: normalized options for a POLL-type session, 2-8 rows per session
-- (US19.3.2). Populated when the facilitator sets the session's config before starting it.
CREATE TABLE IF NOT EXISTS collaboratif.session_poll_option (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    label       VARCHAR(200) NOT NULL,
    sort_order  INTEGER     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_session_poll_option_session ON collaboratif.session_poll_option(session_id);

-- session_poll_state: one row per POLL-type session, tracks whether the facilitator has hidden
-- results from participants (US19.3.2 hide-results/show-results) — absent row = results visible
-- (default), a row's presence with results_hidden=true is what actually withholds counts.
CREATE TABLE IF NOT EXISTS collaboratif.session_poll_state (
    session_id      UUID    NOT NULL PRIMARY KEY REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    results_hidden  BOOLEAN NOT NULL DEFAULT FALSE
);

-- session_poll_vote: one active vote per participant per session (upsert on revote while LIVE,
-- US19.3.2 AC). option_ids is a JSONB array of session_poll_option.id values — a single row
-- covers both single-choice and allowMultiple votes, avoiding a separate join table.
CREATE TABLE IF NOT EXISTS collaboratif.session_poll_vote (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    participant_id  UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    option_ids      JSONB       NOT NULL,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_poll_vote_participant
    ON collaboratif.session_poll_vote(session_id, participant_id);

-- session_wordcloud_entry: one row per distinct normalized word per session, frequency
-- incremented on each new submission of the same word by any participant (US19.3.3 — the entry
-- is intentionally NOT attributed to a single participant, since frequency aggregates across all
-- submitters and a word removed by the facilitator removes all occurrences at once).
CREATE TABLE IF NOT EXISTS collaboratif.session_wordcloud_entry (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    word        VARCHAR(30) NOT NULL,
    frequency   INTEGER     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_session_wordcloud_entry_word
    ON collaboratif.session_wordcloud_entry(session_id, word);

-- session_wordcloud_submission: tracks how many words each participant has submitted to a given
-- WORDCLOUD session, to enforce config.maxWordsPerParticipant (US19.3.3) independently of
-- aggregate word frequency above (a participant re-submitting an already-existing word still
-- counts against their own per-participant limit).
CREATE TABLE IF NOT EXISTS collaboratif.session_wordcloud_submission (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id      UUID        NOT NULL REFERENCES collaboratif.session(id) ON DELETE CASCADE,
    participant_id  UUID        NOT NULL REFERENCES collaboratif.session_participant(id) ON DELETE CASCADE,
    word            VARCHAR(30) NOT NULL,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_session_wordcloud_submission_participant
    ON collaboratif.session_wordcloud_submission(session_id, participant_id);

-- tenant_word_blocklist: tenant-level word blocklist, reusable across every WORDCLOUD session of
-- that tenant (US19.3.3 — config.blocklist).
CREATE TABLE IF NOT EXISTS collaboratif.tenant_word_blocklist (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    word        VARCHAR(30) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_word_blocklist_word
    ON collaboratif.tenant_word_blocklist(tenant_id, word);
