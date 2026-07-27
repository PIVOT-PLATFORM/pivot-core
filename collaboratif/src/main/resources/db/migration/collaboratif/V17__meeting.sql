-- V17: MeetOps meeting creation (US12.1.1, E12) — meetings + agenda_items. Minimal model for
-- this US ("cette US ne couvre que la création" — see pivot-docs/backlog/EPIC-meetops). EN12.1's
-- broader schema (booking_window, event_ref, project_ref, meeting_decisions, meeting_actions,
-- proposed_slots, PRE_RESERVED/CONFIRMED statuses) belongs to later US (US12.4.1, US12.2.x,
-- US12.3.x) and is deliberately not posed here — additive migrations will extend this table
-- rather than guess its shape ahead of the US that actually needs it.
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and the
-- headers of V2..V16 for the precedent this follows): V2..V16 have already been applied against
-- the real, persistent recette-managed Cloud SQL instance by the continuous-deploy pipeline.
-- V17 is therefore additive, never touching V1..V16.

-- meetings: a prepared meeting with a structured agenda, created in DRAFT (US12.1.1). team_id is
-- optional (a meeting may be created for individual/personal use, mirroring collaboratif.session's
-- own optional team_id). status only accepts DRAFT for now — the CHECK constraint is deliberately
-- narrow to this US's scope; widening it to PRE_RESERVED/CONFIRMED (US12.4.1) or animation
-- statuses (US12.2.1) is a later additive migration (ALTER TABLE ... DROP/ADD CONSTRAINT), not
-- guessed ahead of time here.
CREATE TABLE IF NOT EXISTS collaboratif.meetings (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL,
    team_id                  BIGINT       REFERENCES public.teams(id),
    title                    VARCHAR(200) NOT NULL,
    scheduled_at             TIMESTAMPTZ  NOT NULL,
    total_duration_minutes   INTEGER      NOT NULL,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_by               BIGINT       NOT NULL REFERENCES public.users(id),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_meeting_status CHECK (status IN ('DRAFT')),
    CONSTRAINT chk_meeting_total_duration CHECK (total_duration_minutes BETWEEN 1 AND 1440)
);
CREATE INDEX IF NOT EXISTS idx_meetings_tenant_id ON collaboratif.meetings(tenant_id);
CREATE INDEX IF NOT EXISTS idx_meetings_team_id ON collaboratif.meetings(team_id);

-- agenda_items: 0..N ordered points per meeting (US12.1.1 AC2), owned as a JPA aggregate by
-- Meeting (cascade all, orphan removal) — never created/deleted independently of their meeting.
-- position is 0-based, derived server-side from the received array order (never client-supplied
-- as an arbitrary value), so no separate uniqueness constraint is posed on (meeting_id, position)
-- — same reasoning as agilite.standup_participant's participant_order column.
CREATE TABLE IF NOT EXISTS collaboratif.agenda_items (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    meeting_id        UUID         NOT NULL REFERENCES collaboratif.meetings(id) ON DELETE CASCADE,
    position          INTEGER      NOT NULL,
    title             VARCHAR(200) NOT NULL,
    duration_minutes  INTEGER      NOT NULL,
    type              VARCHAR(20)  NOT NULL,
    facilitator       VARCHAR(200),
    CONSTRAINT chk_agenda_item_type CHECK (type IN ('INFO', 'DISCUSSION', 'DECISION')),
    CONSTRAINT chk_agenda_item_duration CHECK (duration_minutes > 0)
);
CREATE INDEX IF NOT EXISTS idx_agenda_items_meeting_id ON collaboratif.agenda_items(meeting_id);
