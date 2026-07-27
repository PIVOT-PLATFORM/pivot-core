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
 * <p><strong>Known, documented gap against the enabler's exact wording.</strong> {@code
 * en-exposer-kpi}'s AC asks for {@code kpi.updated} "signé, idempotent" on "le bus PIVOT (ADR-025,
 * EN28.4)". Spring's {@code ApplicationEventPublisher} is neither signed nor a durable/idempotent
 * delivery mechanism (a same-process synchronous call, replayed on every relevant mutation with no
 * dedup key) — an accurate re-pull nudge for a same-process future consumer, but not yet the
 * platform-bus contract as literally specified. Inherited, not introduced here: {@code
 * SessionKpiUpdatedEvent} (EN19.4) already ships this exact same gap; both wait on a real {@code
 * PivotAdapter}/message-broker bus existing in this codebase before either can close it.
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
