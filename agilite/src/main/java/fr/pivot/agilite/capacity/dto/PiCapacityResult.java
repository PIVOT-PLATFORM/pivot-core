package fr.pivot.agilite.capacity.dto;

/**
 * Result of {@code fr.pivot.agilite.capacity.calc.CapacityCalculator#consolidatePi} (E11 —
 * capacity planning): a PI's capacity, consolidated from its direct sprint children.
 *
 * @param totalJoursHommeNets sum of included sprints' {@link
 *                            EventCapacityResult#totalJoursHommeNets()}
 * @param totalCapaciteNette  sum of included sprints' {@link
 *                            EventCapacityResult#totalCapaciteNette()}
 * @param totalPoints         sum of included sprints' {@link EventCapacityResult#totalPoints()},
 *                            or {@code null} if none of the included sprints tracks points
 * @param includedSprintCount number of sprints contributing to the totals above
 * @param excludedIpSprintCount number of SAFe IP sprints excluded from the totals above
 */
public record PiCapacityResult(
        double totalJoursHommeNets,
        double totalCapaciteNette,
        Double totalPoints,
        int includedSprintCount,
        int excludedIpSprintCount) {
}
