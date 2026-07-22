-- E11 Capacity Planning v2 — moteur complet F11.6 (US11.5.1/US11.6.1-5/US11.7.1/US11.8.1).
--
-- Additive FORWARD migration, continuing V2..V6 (never edit an already-applied migration file —
-- V6__capacity_event.sql shipped Sprint 20, already applied on recette).

-- US11.5.1 — INCREMENT joins PI_PLANNING as a valid parent type; the type check constraint from
-- V6 must be widened (constraints can't be ALTERed in place in Postgres, only dropped/re-added).
ALTER TABLE agilite.capacity_event DROP CONSTRAINT chk_capacity_event_type;
ALTER TABLE agilite.capacity_event ADD CONSTRAINT chk_capacity_event_type
    CHECK (type IN ('PI_PLANNING', 'INCREMENT', 'SPRINT', 'RELEASE', 'CUSTOM'));

-- US11.5.1 — marks a SPRINT child of a PI_PLANNING parent as the IP iteration (excluded from
-- aggregation); harmless/no-op on any other event per the AC.
ALTER TABLE agilite.capacity_event ADD COLUMN is_ip_iteration BOOLEAN NOT NULL DEFAULT false;

-- US11.6.2 — event-level focus factor override, nullable (falls back to team maturity default,
-- then the global 70% default — resolved in CapacitySummaryService, never duplicated per-row).
ALTER TABLE agilite.capacity_event ADD COLUMN focus_factor_percent INTEGER;
ALTER TABLE agilite.capacity_event ADD CONSTRAINT chk_capacity_event_focus_factor
    CHECK (focus_factor_percent IS NULL OR focus_factor_percent BETWEEN 10 AND 100);

-- US11.6.2 — per-member override, takes precedence over the event-level value.
ALTER TABLE agilite.capacity_event_member ADD COLUMN focus_factor_percent INTEGER;
ALTER TABLE agilite.capacity_event_member ADD CONSTRAINT chk_capacity_event_member_focus_factor
    CHECK (focus_factor_percent IS NULL OR focus_factor_percent BETWEEN 10 AND 100);

-- US11.6.1 — tenant-level, manually-entered holiday list; minimal in-module replacement for the
-- permanently-out-of-scope EN22.3 (E22 Roadmap, extracted to the separate Pilotage product).
CREATE TABLE IF NOT EXISTS agilite.capacity_holiday (
    id         UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES public.tenants(id),
    date       DATE         NOT NULL,
    label      VARCHAR(100) NOT NULL,
    CONSTRAINT uq_capacity_holiday_tenant_date UNIQUE (tenant_id, date)
);
CREATE INDEX IF NOT EXISTS idx_capacity_holiday_tenant_id ON agilite.capacity_holiday(tenant_id);

-- US11.6.4 — team agile-maturity level, one row per team, plus an append-only history of changes.
CREATE TABLE IF NOT EXISTS agilite.capacity_team_maturity (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id     BIGINT      NOT NULL REFERENCES public.teams(id),
    maturity    VARCHAR(20) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  BIGINT      NOT NULL REFERENCES public.users(id),
    CONSTRAINT chk_capacity_team_maturity_level CHECK (maturity IN ('FORMING', 'NORMING', 'PERFORMING')),
    CONSTRAINT uq_capacity_team_maturity_team UNIQUE (team_id)
);

CREATE TABLE IF NOT EXISTS agilite.capacity_team_maturity_history (
    id                 UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id          BIGINT      NOT NULL REFERENCES public.tenants(id),
    team_id            BIGINT      NOT NULL REFERENCES public.teams(id),
    previous_maturity  VARCHAR(20),
    new_maturity       VARCHAR(20) NOT NULL,
    changed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_by         BIGINT      NOT NULL REFERENCES public.users(id),
    CONSTRAINT chk_capacity_team_maturity_history_level
        CHECK (new_maturity IN ('FORMING', 'NORMING', 'PERFORMING'))
);
CREATE INDEX IF NOT EXISTS idx_capacity_team_maturity_history_team_id
    ON agilite.capacity_team_maturity_history(team_id);
