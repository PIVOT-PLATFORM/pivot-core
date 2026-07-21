package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityEventType;

import java.util.List;
import java.util.UUID;

/**
 * Full capacity summary for one event (F11.6.5 — capacity summary + F11.6.6 — capacity gauge):
 * per-member breakdown, event totals driven by {@code
 * fr.pivot.agilite.capacity.calc.CapacityCalculator#computeEventCapacity}, an optional PI
 * consolidation ({@code consolidatePi}) when the event is a PI with sprint children, and the
 * engagement gauge.
 *
 * @param eventId                  the event's identifier
 * @param eventType                the kind of event
 * @param eventName                the event's display name
 * @param totalWorkingDays         the event's total working days (calendar minus
 *                                 weekends/holidays, before member-specific absence deduction)
 * @param members                  every member's breakdown, ordered by display position
 * @param totalNetPersonDays       sum of non-excluded members' net person-days (without focus)
 * @param totalNetCapacity         sum of non-excluded members' net capacity (with focus applied)
 * @param totalPoints              sum of non-excluded members' points, or {@code null} if the
 *                                 event has no {@code pointsPerDay}
 * @param totalRecommendedEngagement sum of non-excluded members' recommended engagement
 * @param loadRatio                {@code committedPoints / totalPoints}, or {@code null}
 * @param predictability           {@code completedPoints / committedPoints}, or {@code null}
 * @param consolidation            the PI's consolidated capacity from its direct sprint children,
 *                                 or {@code null} if the event has no children
 * @param gauge                    the engagement gauge (F11.6.6)
 */
public record CapacitySummaryResponse(
        UUID eventId,
        CapacityEventType eventType,
        String eventName,
        int totalWorkingDays,
        List<CapacityMemberBreakdownResponse> members,
        double totalNetPersonDays,
        double totalNetCapacity,
        Double totalPoints,
        double totalRecommendedEngagement,
        Double loadRatio,
        Double predictability,
        PiCapacityResult consolidation,
        CapacityGaugeResponse gauge) {
}
