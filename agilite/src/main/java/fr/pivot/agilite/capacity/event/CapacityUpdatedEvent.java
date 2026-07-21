package fr.pivot.agilite.capacity.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event (v1) signalling that a team's capacity for one sprint/event changed (EN11.2 —
 * Capacity KPI + domain events, Wave 2) — team-level aggregate only, no member-level data (RGPD),
 * same posture as {@code fr.pivot.agilite.capacity.kpi.dto.KpiResponse}.
 *
 * <p>Ported field-for-field from the ticket's {@code { teamRef, sprintRef, capacityPoints }}
 * contract, plus {@link #tenantId()} and {@link #occurredAt()} for parity with this repo's other
 * cross-module domain events ({@code fr.pivot.core.modules.event.ModuleLifecycleEvent}, {@code
 * fr.pivot.notification.event.NotificationCreatedEvent}): those also carry only immutable/
 * primitive types — never a JPA entity — because a {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} consumer runs after the publishing transaction (and its Hibernate session) has
 * already closed.
 *
 * <p><strong>{@code JMSXGroupID} semantics.</strong> This repo has no JMS/message-broker
 * infrastructure today (no {@code JmsTemplate}/{@code jakarta.jms} anywhere in the codebase) —
 * every existing cross-module event travels in-process via {@link
 * org.springframework.context.ApplicationEventPublisher}. {@link #tenantId()} is carried
 * specifically so that a future JMS producer (if one is added) can set it as the message's {@code
 * JMSXGroupID}, guaranteeing per-tenant ordering the same way a single logical partition key
 * would on any grouped queue — but no such producer exists yet; see {@link
 * CapacityUpdatedEventPublisher}'s Javadoc for how this event is actually delivered today.
 *
 * @param tenantId       the owning tenant's {@code public.tenants.id} — the intended {@code
 *                        JMSXGroupID} partition key, see class Javadoc
 * @param teamRef         the owning PIVOT team's {@code public.teams.id} (team-level aggregate,
 *                        never a member id)
 * @param sprintRef       the capacity event ({@code agilite.capacity_event.id}) whose capacity
 *                        changed — named {@code sprintRef} per the ticket's contract, though the
 *                        referenced event need not be {@code SPRINT}-typed (it may be a PI,
 *                        release, or custom event)
 * @param capacityPoints  the event's recomputed net capacity in points at the time of the
 *                        mutation ({@code EventCapacityResult#totalPoints()}), or {@code 0} if the
 *                        event tracks no {@code pointsPerDay}
 * @param occurredAt      when the underlying capacity mutation was committed
 */
public record CapacityUpdatedEvent(
        Long tenantId,
        Long teamRef,
        UUID sprintRef,
        double capacityPoints,
        Instant occurredAt) {
}
