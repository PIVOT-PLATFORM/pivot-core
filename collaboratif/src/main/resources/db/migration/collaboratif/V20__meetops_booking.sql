-- V20: MeetOps booking flow (US12.4.1, E12) — pre-reservation from a roadmap event window +
-- best-slot proposal + organizer confirmation. Additive on top of V17 (US12.1.1's meetings/
-- agenda_items) — never touches V1..V19.
--
-- Convention note (see CLAUDE.md, "Migrations Flyway — fichier V1 unique avant la BETA", and the
-- header of V17 for the precedent this follows): V1..V19 have already been applied against the
-- real, persistent recette-managed Cloud SQL instance by the continuous-deploy pipeline. V20 is
-- therefore additive and forward-only.

-- meetings: widen chk_meeting_status (V17 restricted it to 'DRAFT' only) to also accept
-- PRE_RESERVED/CONFIRMED (US12.4.1's booking-flow statuses, MeetingStatus enum) — DRAFT itself is
-- untouched, US12.1.1's flow is unaffected. booking_window_*/event_ref/project_ref are the
-- correlation fields for a meeting created from a roadmap.event.window.* event; event_ref/
-- project_ref are plain strings, never a foreign key (ADR-006/008 — no cross-module FK).
-- reschedule_requested_at supports the "cohérence window.updated/deleted" AC: a window event
-- received on an already-CONFIRMED meeting must never silently cancel it, only flag a pending
-- reprogramming request.
ALTER TABLE collaboratif.meetings DROP CONSTRAINT IF EXISTS chk_meeting_status;
ALTER TABLE collaboratif.meetings
    ADD CONSTRAINT chk_meeting_status CHECK (status IN ('DRAFT', 'PRE_RESERVED', 'CONFIRMED'));

ALTER TABLE collaboratif.meetings ADD COLUMN IF NOT EXISTS booking_window_start TIMESTAMPTZ;
ALTER TABLE collaboratif.meetings ADD COLUMN IF NOT EXISTS booking_window_end TIMESTAMPTZ;
ALTER TABLE collaboratif.meetings ADD COLUMN IF NOT EXISTS event_ref VARCHAR(100);
ALTER TABLE collaboratif.meetings ADD COLUMN IF NOT EXISTS project_ref VARCHAR(100);
ALTER TABLE collaboratif.meetings ADD COLUMN IF NOT EXISTS reschedule_requested_at TIMESTAMPTZ;

-- created_by (V17: NOT NULL, references public.users) must become nullable for the booking flow:
-- a roadmap.event.window.created payload carries no explicit organizer field, so the organizer is
-- best-effort-resolved from participants[] (see BookingService's Javadoc) and may not resolve to
-- a platform user at all.
ALTER TABLE collaboratif.meetings ALTER COLUMN created_by DROP NOT NULL;

-- Idempotence AC: a second window.created for an already-known event_ref must upsert, never
-- duplicate. Partial (event_ref IS NOT NULL) so US12.1.1 manually-created meetings (event_ref
-- always NULL) are entirely unaffected by this constraint.
CREATE UNIQUE INDEX IF NOT EXISTS uq_meeting_event_ref
    ON collaboratif.meetings(tenant_id, event_ref) WHERE event_ref IS NOT NULL;

-- meeting_participants: raw participant identifiers (e-mail, as carried by participants[] on the
-- roadmap event) attached to a booking-flow meeting. Deliberately NOT a FK to public.users:
-- participants[] entries are not guaranteed to resolve to a registered platform account (RGPD/
-- ADR-006/008 — no cross-module FK, correlation only). participant_user_id is a best-effort,
-- same-tenant, by-email resolution performed at consumption time (used for organizer/participant
-- STOMP + confirm-authorization checks) and stays NULL when it cannot be resolved.
CREATE TABLE IF NOT EXISTS collaboratif.meeting_participants (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    meeting_id          UUID         NOT NULL REFERENCES collaboratif.meetings(id) ON DELETE CASCADE,
    participant_ref     VARCHAR(320) NOT NULL,
    participant_user_id BIGINT       REFERENCES public.users(id)
);
CREATE INDEX IF NOT EXISTS idx_meeting_participants_meeting_id
    ON collaboratif.meeting_participants(meeting_id);
CREATE INDEX IF NOT EXISTS idx_meeting_participants_user_id
    ON collaboratif.meeting_participants(participant_user_id);

-- proposed_slots: N candidate slots ranked by the best-slot engine for a PRE_RESERVED meeting.
-- rank = 1 is the recommended slot (proposed by default, AC "meilleur créneau"). has_conflict/
-- conflict_reason surface the "aucun créneau sans conflit" AC (moins-mauvais-créneau, flagged
-- explicitly rather than silently proposed as if perfect).
CREATE TABLE IF NOT EXISTS collaboratif.proposed_slots (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    meeting_id       UUID         NOT NULL REFERENCES collaboratif.meetings(id) ON DELETE CASCADE,
    slot_start       TIMESTAMPTZ  NOT NULL,
    slot_end         TIMESTAMPTZ  NOT NULL,
    rank             INTEGER      NOT NULL,
    has_conflict     BOOLEAN      NOT NULL DEFAULT FALSE,
    conflict_reason  VARCHAR(200),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_proposed_slot_order CHECK (slot_end > slot_start),
    CONSTRAINT uq_proposed_slot_rank UNIQUE (meeting_id, rank)
);
CREATE INDEX IF NOT EXISTS idx_proposed_slots_meeting ON collaboratif.proposed_slots(meeting_id);
