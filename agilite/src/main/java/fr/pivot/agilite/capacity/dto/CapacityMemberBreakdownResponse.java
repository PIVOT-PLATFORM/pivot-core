package fr.pivot.agilite.capacity.dto;

import java.util.UUID;

/**
 * One member's row in a capacity event summary (F11.6.5 — capacity summary), combining the
 * {@code CapacityEventMember} roster snapshot (name/role/quotite) with its computed {@link
 * MemberCapacityResult}.
 *
 * @param memberId             the {@code CapacityEventMember} identifier
 * @param name                 the member's display name (roster snapshot)
 * @param role                 the member's role (roster snapshot), or {@code null}
 * @param quotite              the full-time-equivalent quotity, {@code 1} = full-time
 * @param excluded             whether this member is excluded from the event's aggregate totals
 * @param effectiveFocus       the resolved focus factor actually applied (member &gt; role &gt;
 *                             event &gt; maturity default precedence)
 * @param absentWorkingDays    working days lost to absences, weighted by each absence's fraction
 * @param workedDays           net person-days without focus applied ({@code
 *                             MemberCapacityResult#joursHommeNets()})
 * @param netCapacity          net capacity with focus applied ({@code
 *                             MemberCapacityResult#capaciteNette()})
 * @param points               {@code workedDays × pointsPerDay}, or {@code null} if the event has
 *                             no {@code pointsPerDay}
 * @param recommendedEngagement the recommended engagement — {@code netCapacity × (1 − margin)}
 */
public record CapacityMemberBreakdownResponse(
        UUID memberId,
        String name,
        String role,
        double quotite,
        boolean excluded,
        double effectiveFocus,
        double absentWorkingDays,
        double workedDays,
        double netCapacity,
        Double points,
        double recommendedEngagement) {
}
