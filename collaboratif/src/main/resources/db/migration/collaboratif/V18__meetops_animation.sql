-- V18: MeetOps meeting animation (US12.2.1, E12) — real-time animation of a meeting (current
-- point + timer), minimal in-meeting decision/action capture. Additive only: V1..V17 (V17 =
-- US12.1.1's meetings/agenda_items creation) are never touched by this file, per this repo's
-- "V1 unique avant la BETA" Flyway convention.

-- meetings: widen the lifecycle status CHECK constraint from US12.1.1's DRAFT-only shape to also
-- accept CONFIRMED (US12.4.1 booking flow, not yet producible — see MeetingStatus's own JavaDoc
-- for why this US's AC-01 needs it in the enum regardless), IN_PROGRESS and ENDED (this US).
-- Deliberately DROP+ADD rather than a fresh CREATE — same column, same table, only its allowed
-- value set changes.
ALTER TABLE collaboratif.meetings DROP CONSTRAINT chk_meeting_status;
ALTER TABLE collaboratif.meetings ADD CONSTRAINT chk_meeting_status
    CHECK (status IN ('DRAFT', 'CONFIRMED', 'IN_PROGRESS', 'ENDED'));

-- meetings: animation columns. current_agenda_item_id references agenda_items — a circular FK
-- with agenda_items.meeting_id (which references meetings) is legal in Postgres since both rows
-- always exist before this column is ever set (it starts NULL and is only populated by an UPDATE
-- once both the meeting and its agenda items are already persisted, never at INSERT time).
ALTER TABLE collaboratif.meetings ADD COLUMN current_agenda_item_id UUID
    REFERENCES collaboratif.agenda_items(id);
ALTER TABLE collaboratif.meetings ADD COLUMN started_at TIMESTAMPTZ;
ALTER TABLE collaboratif.meetings ADD COLUMN ended_at TIMESTAMPTZ;
ALTER TABLE collaboratif.meetings ADD COLUMN auto_advance BOOLEAN NOT NULL DEFAULT FALSE;

-- agenda_items: animation columns. item_status defaults PENDING so every item created by
-- US12.1.1's existing INSERT path (which knows nothing of this US) is immediately valid without
-- a data backfill. current_item_started_at is the sole timer authority (AC-S4) — elapsed/
-- remaining/overtime are always computed server-side from it, never accepted from a client.
ALTER TABLE collaboratif.agenda_items ADD COLUMN item_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE collaboratif.agenda_items ADD CONSTRAINT chk_agenda_item_item_status
    CHECK (item_status IN ('PENDING', 'CURRENT', 'DONE'));
ALTER TABLE collaboratif.agenda_items ADD COLUMN current_item_started_at TIMESTAMPTZ;
ALTER TABLE collaboratif.agenda_items ADD COLUMN ended_at TIMESTAMPTZ;
ALTER TABLE collaboratif.agenda_items ADD COLUMN actual_seconds INTEGER;
ALTER TABLE collaboratif.agenda_items ADD COLUMN overtime BOOLEAN NOT NULL DEFAULT FALSE;

-- meeting_decisions: minimal decision-capture schema, posed now alongside meeting_actions per
-- EN12.2's broader additive design so a later US (US12.2.2) never needs to guess this table's
-- shape ahead of time — NOT used by any code in this US (see pivot-docs "Hors-périmètre":
-- decisions capture is out of US12.2.1's scope, only actions are captured here).
CREATE TABLE IF NOT EXISTS collaboratif.meeting_decisions (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL,
    meeting_id       UUID        NOT NULL REFERENCES collaboratif.meetings(id) ON DELETE CASCADE,
    agenda_item_id   UUID        REFERENCES collaboratif.agenda_items(id),
    label            TEXT        NOT NULL,
    decided_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       BIGINT
);
CREATE INDEX IF NOT EXISTS idx_meeting_decisions_tenant_meeting
    ON collaboratif.meeting_decisions(tenant_id, meeting_id);

-- meeting_actions: minimal in-meeting action capture (US12.2.1 AC-08) — label + owner_user_id +
-- due_date is deliberately the whole model; the fuller assignment/follow-up workflow belongs to
-- US12.3.1/US12.3.2.
CREATE TABLE IF NOT EXISTS collaboratif.meeting_actions (
    id               UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL,
    meeting_id       UUID        NOT NULL REFERENCES collaboratif.meetings(id) ON DELETE CASCADE,
    agenda_item_id   UUID        REFERENCES collaboratif.agenda_items(id),
    label            TEXT        NOT NULL,
    owner_user_id    BIGINT,
    due_date         DATE,
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_meeting_actions_tenant_meeting
    ON collaboratif.meeting_actions(tenant_id, meeting_id);
