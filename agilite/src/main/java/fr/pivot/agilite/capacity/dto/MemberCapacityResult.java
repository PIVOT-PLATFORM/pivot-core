package fr.pivot.agilite.capacity.dto;

/**
 * Result of {@code fr.pivot.agilite.capacity.calc.CapacityCalculator#computeMemberCapacity} for
 * one member (E11 — capacity planning).
 *
 * @param memberId              the input member's {@link CapacityMemberInput#id()}
 * @param effectiveFocus        the resolved focus factor actually applied (member &gt; role &gt;
 *                              event &gt; maturity default precedence)
 * @param absentWorkingDays     working days lost to absences, weighted by each absence's {@code
 *                              fraction} (rounded to 2 decimals)
 * @param joursHommeNets        net person-days <strong>without</strong> focus applied — {@code
 *                              (eventWorkingDays − absentWorkingDays) × quotite} — feeds {@code
 *                              points} (never focus-adjusted a second time)
 * @param capaciteNette         net capacity <strong>with</strong> focus applied exactly once —
 *                              {@code joursHommeNets × effectiveFocus}
 * @param points                {@code joursHommeNets × pointsPerDay}, or {@code null} if the
 *                              event has no {@code pointsPerDay}
 * @param engagementRecommande  the recommended engagement — {@code capaciteNette × (1 − margin)}
 */
public record MemberCapacityResult(
        String memberId,
        double effectiveFocus,
        double absentWorkingDays,
        double joursHommeNets,
        double capaciteNette,
        Double points,
        double engagementRecommande) {
}
