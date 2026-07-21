package fr.pivot.agilite.capacity.event;

import java.time.Instant;

/**
 * Domain event (v1) signalling that a team's E11 KPIs (see {@code
 * fr.pivot.agilite.capacity.kpi.KpiService}) are now stale and worth recomputing — the {@code
 * kpi.updated} convention this ticket (EN11.2) introduces for this module. Carries no KPI values
 * itself: {@link fr.pivot.agilite.capacity.kpi.KpiController} stays a pull-model endpoint (see its
 * Javadoc) recomputed on every {@code GET}, so this event is only a "something changed for this
 * team" signal for a future push-side consumer (e.g. a WebSocket broadcast or cache invalidation)
 * — not the KPI payload.
 *
 * @param tenantId  the owning tenant's {@code public.tenants.id}
 * @param teamRef   the PIVOT team whose KPIs changed
 * @param occurredAt when this signal was raised
 */
public record KpiUpdatedEvent(Long tenantId, Long teamRef, Instant occurredAt) {
}
