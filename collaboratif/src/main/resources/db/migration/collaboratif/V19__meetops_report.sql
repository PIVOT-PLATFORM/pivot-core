-- V19: MeetOps meeting report (US12.3.1, E12) — the figé (frozen) compte-rendu snapshot written
-- once, at meeting closure. Additive only: V1..V18 are never touched by this file, per this
-- repo's "V1 unique avant la BETA" Flyway convention.
--
-- Design (Gate 1 decision, pivot-docs backlog "compte-rendu" architecture note): hybrid
-- draft/frozen approach. While a meeting is not yet ENDED, its report is a live PROJECTION
-- derived on every read from meetings/agenda_items/meeting_decisions/meeting_actions — no
-- storage needed. Once a meeting closes (POST .../end, US12.2.1 — this US does not add a
-- separate /close route, see MeetingReportService's own Javadoc), the report is FROZEN into this
-- table so that (1) the shared CR stays immutable even though decisions/actions remain editable
-- afterward (US12.3.2), (2) actualDurationSeconds is computed once, at the moment it is finally
-- stable, and (3) re-reading a closed meeting's report never re-aggregates four tables.

-- meeting_report: exactly one frozen snapshot per meeting (UNIQUE meeting_id) — a second closure
-- attempt is prevented upstream (a meeting's status transition to ENDED is itself a one-time,
-- terminal event; see Meeting#end), not by an upsert here.
CREATE TABLE IF NOT EXISTS collaboratif.meeting_report (
    id                       BIGSERIAL    PRIMARY KEY,
    meeting_id               UUID         NOT NULL UNIQUE
                                           REFERENCES collaboratif.meetings(id) ON DELETE CASCADE,
    tenant_id                BIGINT       NOT NULL,
    content                  JSONB        NOT NULL,
    actual_duration_seconds  INTEGER,
    generated_by             BIGINT       NOT NULL,
    generated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_meeting_report_tenant ON collaboratif.meeting_report(tenant_id);
