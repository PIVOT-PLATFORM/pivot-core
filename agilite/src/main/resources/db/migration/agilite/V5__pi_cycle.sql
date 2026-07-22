-- E50 PI Planning socle — US50.1.1/US50.3.1/US50.3.2.
--
-- Additive FORWARD migration, same precedent as V2/V3/V4: a persistent database (recette)
-- already carries earlier-deploy rows, and Flyway never re-runs V1 there, so a V1 edit would
-- never reach recette. Fresh environments apply V1..V5 in order, ending in the same state.
--
-- FK convention mirrors agilite.wheel/standup_session: tenant_id -> public.tenants (no ON DELETE
-- CASCADE, soft-delete/deactivation model), created_by -> public.users. A PI cycle has no single
-- owning team (unlike wheel/standup) — it gathers several Train teams, see pi_cycle_team below.
CREATE TABLE IF NOT EXISTS agilite.pi_cycle (
    id              UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES public.tenants(id),
    name            VARCHAR(120) NOT NULL,
    art_name        VARCHAR(120),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PREPARATION',
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    -- Reserved for US50.2.1 (logistics form, blocked on E42/E49) — posed now to avoid an
    -- additive migration later; unused until then.
    event_day_1     DATE,
    event_day_2     DATE,
    event_location  VARCHAR(200),
    created_by      BIGINT       NOT NULL REFERENCES public.users(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_pi_cycle_status CHECK (status IN ('PREPARATION', 'ACTIVE', 'CLOSED'))
);
CREATE INDEX IF NOT EXISTS idx_pi_cycle_tenant_id ON agilite.pi_cycle(tenant_id);

CREATE TABLE IF NOT EXISTS agilite.pi_iteration (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    cycle_id    UUID         NOT NULL REFERENCES agilite.pi_cycle(id) ON DELETE CASCADE,
    number      INTEGER      NOT NULL,
    label       VARCHAR(50)  NOT NULL,
    start_date  DATE         NOT NULL,
    end_date    DATE         NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_pi_iteration_cycle_id ON agilite.pi_iteration(cycle_id);

-- team_order: "order" is a reserved SQL keyword (same rename precedent as
-- agilite.standup_participant.participant_order). color has no source in public.teams (no color
-- column there) — always server-assigned from a fixed palette, never copied.
CREATE TABLE IF NOT EXISTS agilite.pi_cycle_team (
    id              UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    cycle_id        UUID         NOT NULL REFERENCES agilite.pi_cycle(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    color           VARCHAR(20)  NOT NULL,
    team_order      INTEGER      NOT NULL,
    -- Nullable: ON DELETE SET NULL lets a later public.teams deletion clear this reference
    -- without breaking a historical PI — name/color are already denormalized above for exactly
    -- this reason (US50.1.1 AC).
    source_team_id  BIGINT       REFERENCES public.teams(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_pi_cycle_team_cycle_id ON agilite.pi_cycle_team(cycle_id);
CREATE INDEX IF NOT EXISTS idx_pi_cycle_team_source_team_id ON agilite.pi_cycle_team(source_team_id);

-- teamId/iterationId null = Train row / "Unplanned" column (US50.3.1 AC) — ON DELETE SET NULL so
-- deleting a Train team or an iteration falls a ticket back to those defaults, never deletes it.
CREATE TABLE IF NOT EXISTS agilite.pi_ticket (
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    cycle_id      UUID         NOT NULL REFERENCES agilite.pi_cycle(id) ON DELETE CASCADE,
    type          VARCHAR(20)  NOT NULL,
    title         VARCHAR(300) NOT NULL,
    description   VARCHAR(3000),
    team_id       UUID         REFERENCES agilite.pi_cycle_team(id) ON DELETE SET NULL,
    iteration_id  UUID         REFERENCES agilite.pi_iteration(id) ON DELETE SET NULL,
    ticket_order  INTEGER      NOT NULL,
    CONSTRAINT chk_pi_ticket_type CHECK (type IN ('FEATURE', 'MILESTONE', 'RISK', 'OBJECTIVE', 'STORY', 'ENABLER'))
);
CREATE INDEX IF NOT EXISTS idx_pi_ticket_cycle_id ON agilite.pi_ticket(cycle_id);
CREATE INDEX IF NOT EXISTS idx_pi_ticket_team_id ON agilite.pi_ticket(team_id);
CREATE INDEX IF NOT EXISTS idx_pi_ticket_iteration_id ON agilite.pi_ticket(iteration_id);

CREATE TABLE IF NOT EXISTS agilite.pi_dependency (
    id              UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    cycle_id        UUID         NOT NULL REFERENCES agilite.pi_cycle(id) ON DELETE CASCADE,
    from_ticket_id  UUID         NOT NULL REFERENCES agilite.pi_ticket(id) ON DELETE CASCADE,
    to_ticket_id    UUID         NOT NULL REFERENCES agilite.pi_ticket(id) ON DELETE CASCADE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OK',
    note            VARCHAR(1000),
    CONSTRAINT chk_pi_dependency_status CHECK (status IN ('OK', 'BLOCKED')),
    CONSTRAINT uq_pi_dependency_pair UNIQUE (from_ticket_id, to_ticket_id)
);
CREATE INDEX IF NOT EXISTS idx_pi_dependency_cycle_id ON agilite.pi_dependency(cycle_id);
