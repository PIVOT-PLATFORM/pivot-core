package fr.pivot.agilite.capacity.dto;

import java.util.List;

/**
 * Result of {@code fr.pivot.agilite.capacity.calc.CapacityCalculator#computeEventCapacity} (E11 —
 * capacity planning).
 *
 * <p>{@link #members()} lists <strong>every</strong> member, including {@link
 * CapacityMemberInput#excluded()} ones (so a caller can still display them) — the {@code total*}
 * aggregates below sum only the non-excluded members.
 *
 * @param totalWorkingDays        the event's total working days (calendar minus weekends/holidays,
 *                                 before any member-specific absence deduction)
 * @param members                  every member's individual result, ordered by {@link
 *                                 CapacityMemberInput#position()}
 * @param totalJoursHommeNets      sum of non-excluded members' {@link
 *                                 MemberCapacityResult#joursHommeNets()}
 * @param totalCapaciteNette       sum of non-excluded members' {@link
 *                                 MemberCapacityResult#capaciteNette()}
 * @param totalPoints              sum of non-excluded members' {@link
 *                                 MemberCapacityResult#points()}, or {@code null} if the event has
 *                                 no {@code pointsPerDay}
 * @param totalEngagementRecommande sum of non-excluded members' {@link
 *                                 MemberCapacityResult#engagementRecommande()}
 * @param loadRatio                {@code committedPoints / totalPoints}, or {@code null} if either
 *                                 operand is unavailable or {@code totalPoints <= 0}
 * @param predictability           {@code completedPoints / committedPoints}, or {@code null} if
 *                                 either operand is unavailable or {@code committedPoints <= 0}
 */
public record EventCapacityResult(
        int totalWorkingDays,
        List<MemberCapacityResult> members,
        double totalJoursHommeNets,
        double totalCapaciteNette,
        Double totalPoints,
        double totalEngagementRecommande,
        Double loadRatio,
        Double predictability) {
}
