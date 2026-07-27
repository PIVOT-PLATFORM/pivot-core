package fr.pivot.collaboratif.session.kpi;

import java.time.Instant;

/**
 * The {@code kpi.updated} signal (EN28.14 push surface, EN28.4 bus) for one Session live KPI —
 * this module's in-process implementation of that convention via Spring's {@code
 * ApplicationEventPublisher}, the same mechanism every other cross-module event in this repo
 * already uses (there is no dedicated {@code PivotAdapter}/message-broker bus in this codebase
 * today — see {@link SessionKpiEventPublisher}'s Javadoc).
 *
 * <p>Carries no value — {@code fr.pivot.collaboratif.kpi.CollaboratifKpiController}'s pull
 * endpoint stays the source of truth, recomputed on every {@code GET} (see {@link
 * SessionKpiService}'s Javadoc), so this is only a
 * "this KPI is worth re-pulling" signal for a future push-side consumer (e.g. a cache
 * invalidation or a dashboard live-refresh), not the payload itself.
 *
 * @param tenantId   the owning tenant's {@code public.tenants.id}
 * @param teamId     the session's owning team, or {@code null} for a team-less session — a
 *                   consumer of a team-scoped KPI (e.g. {@code session.completion_rate}) should
 *                   ignore events carrying a {@code null} teamId
 * @param kpiKey     the affected KPI's stable identifier
 * @param occurredAt when the underlying session mutation that triggered this recalculation was
 *                   committed
 */
public record SessionKpiUpdatedEvent(Long tenantId, Long teamId, String kpiKey, Instant occurredAt) {
}
