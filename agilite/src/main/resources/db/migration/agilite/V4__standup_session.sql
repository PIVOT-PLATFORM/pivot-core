-- E10 Daily Standup — US10.1.1/US10.1.2/US10.2.1/US10.2.2/US10.3.1.
--
-- Additive FORWARD migration, same precedent as V2/V3 (poker): a persistent database (recette)
-- already carries earlier-deploy rows, and Flyway never re-runs V1 there, so a V1 edit would
-- never reach recette. Fresh environments apply V1..V4 in order, ending in the same state.
--
-- FK convention mirrors agilite.wheel (US14.1.1): tenant_id -> public.tenants (no ON DELETE
-- CASCADE, soft-delete/deactivation model), team_id -> public.teams ON DELETE CASCADE (a deleted
-- team takes its standup sessions with it), created_by -> public.users.
CREATE TABLE IF NOT EXISTS agilite.standup_session (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id                  BIGINT       NOT NULL REFERENCES public.teams(id) ON DELETE CASCADE,
    name                     VARCHAR(100) NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    current_index            INTEGER      NOT NULL DEFAULT 0,
    time_per_person_seconds  INTEGER      NOT NULL DEFAULT 120,
    started_at               TIMESTAMPTZ,
    ended_at                 TIMESTAMPTZ,
    created_by               BIGINT       NOT NULL REFERENCES public.users(id),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_standup_session_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE')),
    CONSTRAINT chk_standup_session_time_per_person CHECK (time_per_person_seconds BETWEEN 30 AND 1800)
);
CREATE INDEX IF NOT EXISTS idx_standup_session_tenant_id ON agilite.standup_session(tenant_id);
CREATE INDEX IF NOT EXISTS idx_standup_session_team_id   ON agilite.standup_session(team_id);
CREATE INDEX IF NOT EXISTS idx_standup_session_status    ON agilite.standup_session(status);

-- participant_order: "order" is a reserved SQL keyword, hence the column rename vs. the Java
-- field's own name choice (StandupParticipant#getParticipantOrder). No unique index on
-- (session_id, participant_order): the US10.2.2 reorder AC rewrites orders in-place across
-- several rows within one transaction, and a strict uniqueness constraint would otherwise reject
-- a transient duplicate mid-update depending on statement execution order — not worth the
-- DEFERRABLE INITIALLY DEFERRED complexity for a value whose integrity is already fully owned by
-- StandupSessionService (never exposed to a client-supplied arbitrary value).
CREATE TABLE IF NOT EXISTS agilite.standup_participant (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    session_id        UUID         NOT NULL REFERENCES agilite.standup_session(id) ON DELETE CASCADE,
    -- Nullable despite always being populated at creation time: ON DELETE SET NULL lets a later
    -- team_members deletion clear this reference without breaking session history — name is
    -- already denormalized below for exactly this reason (US10.1.1 AC).
    team_member_id    BIGINT       REFERENCES public.team_members(id) ON DELETE SET NULL,
    name              VARCHAR(200) NOT NULL,
    participant_order INTEGER      NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'WAITING',
    speaking_at       TIMESTAMPTZ,
    done_speaking     TIMESTAMPTZ,
    extra_seconds     INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT chk_standup_participant_status CHECK (status IN ('WAITING', 'SPEAKING', 'DONE', 'SKIPPED'))
);
CREATE INDEX IF NOT EXISTS idx_standup_participant_session_id ON agilite.standup_participant(session_id);
