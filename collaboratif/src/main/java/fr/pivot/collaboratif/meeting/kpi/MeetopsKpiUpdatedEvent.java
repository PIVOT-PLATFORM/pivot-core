package fr.pivot.collaboratif.meeting.kpi;

import java.time.Instant;

/**
 * The {@code kpi.updated} signal (EN28.14 push surface, EN28.4 bus) for one MeetOps KPI — this
 * module's in-process implementation via Spring's {@code ApplicationEventPublisher}, mirroring
 * {@code fr.pivot.collaboratif.session.kpi.SessionKpiUpdatedEvent}'s own shape and rationale for
 * EN19.4 (there is no dedicated {@code PivotAdapter}/message-broker bus in this codebase today).
 *
 * <p>Carries no value — {@link MeetopsKpiEventPublisher}'s pull endpoint stays the source of
 * truth, recomputed on every {@code GET} (see {@link MeetopsKpiService}'s Javadoc), so this is
 * only a "this KPI is worth re-pulling" signal for a future push-side consumer, not the payload
 * itself.
 *
 * @param tenantId   the owning tenant's {@code public.tenants.id}
 * @param teamId     the meeting's owning team, or {@code null} for a team-less (personal) meeting
 *                   — a consumer of a team-scoped KPI (every MeetOps KPI but {@code
 *                   meetops.meetings_run}) should ignore events carrying a {@code null} teamId
 * @param kpiKey     the affected KPI's stable identifier
 * @param occurredAt when the underlying meeting mutation that triggered this recalculation was
 *                   committed
 */
public record MeetopsKpiUpdatedEvent(Long tenantId, Long teamId, String kpiKey, Instant occurredAt) {
}
