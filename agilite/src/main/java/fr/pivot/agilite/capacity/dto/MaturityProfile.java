package fr.pivot.agilite.capacity.dto;

/**
 * Default profile associated with a {@code fr.pivot.agilite.capacity.CapacityMaturityLevel} (E11
 * — capacity planning) — see {@code
 * fr.pivot.agilite.capacity.calc.CapacityCalculator#maturityProfile} for the exact table.
 *
 * @param focusFactor       default focus factor, in {@code [0, 1]}
 * @param margin            default safety margin, in {@code [0, 1]}
 * @param velocityMultiplier maturity-driven adjustment applied to a sprint's raw {@code
 *                          totalCapaciteNette} by {@code
 *                          CapacityCalculator#maturityAdjustedCapacity} — a less mature team's raw
 *                          person-day capacity is tempered down towards its likely real output
 */
public record MaturityProfile(double focusFactor, double margin, double velocityMultiplier) {
}
