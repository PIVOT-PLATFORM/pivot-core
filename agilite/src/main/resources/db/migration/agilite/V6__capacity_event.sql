-- E11 Capacity Planning v1 socle — US11.1.1/US11.1.2/US11.2.1/US11.2.2/US11.3.1/US11.4.1/US11.4.2.
--
-- Additive FORWARD migration, same precedent as V2..V5: a persistent database (recette)
-- already carries earlier-deploy rows, and Flyway never re-runs V1 there, so a V1 edit would
-- never reach recette. Fresh environments apply V1..V6 in order, ending in the same state.
--
-- FK convention mirrors agilite.wheel/pi_cycle: tenant_id/team_id/created_by -> public.* without
-- ON DELETE CASCADE (soft-delete/deactivation model) ; owned child rows cascade on their parent
-- event/member. parent_event_id has no ON DELETE action (defaults to RESTRICT): the service layer
-- already refuses (409) deleting an event with children before any DELETE reaches the database,
-- so no cascade backstop is needed there.
CREATE TABLE IF NOT EXISTS agilite.capacity_event (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL REFERENCES public.tenants(id),
    team_id           BIGINT       NOT NULL REFERENCES public.teams(id),
    parent_event_id   UUID         REFERENCES agilite.capacity_event(id),
    type              VARCHAR(20)  NOT NULL,
    name              VARCHAR(120) NOT NULL,
    start_date        DATE         NOT NULL,
    end_date          DATE         NOT NULL,
    points_per_day    DOUBLE PRECISION,
    committed_points  INTEGER,
    completed_points  INTEGER,
    created_by        BIGINT       NOT NULL REFERENCES public.users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_capacity_event_type CHECK (type IN ('PI_PLANNING', 'SPRINT', 'RELEASE', 'CUSTOM'))
);
CREATE INDEX IF NOT EXISTS idx_capacity_event_tenant_id ON agilite.capacity_event(tenant_id);
CREATE INDEX IF NOT EXISTS idx_capacity_event_team_id ON agilite.capacity_event(team_id);
CREATE INDEX IF NOT EXISTS idx_capacity_event_parent_event_id ON agilite.capacity_event(parent_event_id);

-- Auto-seeded once at event creation from the team's current roster (US11.2.1) — name
-- denormalized so a later team-member departure never breaks a historical event's roster, same
-- precedent as agilite.standup_participant/pi_cycle_team.
CREATE TABLE IF NOT EXISTS agilite.capacity_event_member (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_id              UUID         NOT NULL REFERENCES agilite.capacity_event(id) ON DELETE CASCADE,
    team_member_id        BIGINT       NOT NULL REFERENCES public.team_members(id),
    name                  VARCHAR(200) NOT NULL,
    availability_percent  INTEGER      NOT NULL DEFAULT 100,
    excluded              BOOLEAN      NOT NULL DEFAULT false,
    CONSTRAINT chk_capacity_event_member_availability CHECK (availability_percent BETWEEN 10 AND 100)
);
CREATE INDEX IF NOT EXISTS idx_capacity_event_member_event_id ON agilite.capacity_event_member(event_id);

-- RGPD minimisation (US11.2.2 §Architecture, explicit maintainer decision) — dates only, no
-- reason/category/comment column at all, not even nullable.
CREATE TABLE IF NOT EXISTS agilite.capacity_absence (
    id               UUID   NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_member_id  UUID   NOT NULL REFERENCES agilite.capacity_event_member(id) ON DELETE CASCADE,
    date_debut       DATE   NOT NULL,
    date_fin         DATE   NOT NULL,
    CONSTRAINT chk_capacity_absence_dates CHECK (date_fin >= date_debut)
);
CREATE INDEX IF NOT EXISTS idx_capacity_absence_event_member_id ON agilite.capacity_absence(event_member_id);

-- One row per (event_id, date) — the unique constraint backs the idempotent upsert AC (US11.4.2).
CREATE TABLE IF NOT EXISTS agilite.capacity_burndown_entry (
    id                UUID    NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_id          UUID    NOT NULL REFERENCES agilite.capacity_event(id) ON DELETE CASCADE,
    entry_date        DATE    NOT NULL,
    points_remaining  INTEGER NOT NULL,
    CONSTRAINT chk_capacity_burndown_points CHECK (points_remaining >= 0),
    CONSTRAINT uq_capacity_burndown_event_date UNIQUE (event_id, entry_date)
);
CREATE INDEX IF NOT EXISTS idx_capacity_burndown_entry_event_id ON agilite.capacity_burndown_entry(event_id);
