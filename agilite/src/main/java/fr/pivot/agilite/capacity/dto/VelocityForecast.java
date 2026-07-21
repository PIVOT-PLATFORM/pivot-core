package fr.pivot.agilite.capacity.dto;

/**
 * Result of {@code fr.pivot.agilite.capacity.calc.CapacityCalculator#forecastVelocity} (E11 —
 * capacity planning): a rolling-window velocity forecast derived from a team's last N sprints.
 *
 * @param sampleSize              number of non-empty sprints actually used (after excluding
 *                                 empty/{@code null} entries from the requested window)
 * @param mean                    the rolling average of the sample, rounded to 2 decimals
 * @param stdDev                  the sample's (population) standard deviation, rounded to 2
 *                                 decimals
 * @param coefficientOfVariation  {@code stdDev / mean} ({@code CV}), rounded to 2 decimals —
 *                                 {@code 0} when {@code mean == 0}
 * @param lowerBound              {@code mean − sigmaMultiplier × stdDev}, rounded to 2 decimals
 * @param upperBound              {@code mean + sigmaMultiplier × stdDev}, rounded to 2 decimals
 * @param widened                 {@code true} if {@code coefficientOfVariation > 0.25} — the
 *                                 confidence interval above used the wider sigma multiplier;
 *                                 {@code false} means the tightened multiplier was used
 */
public record VelocityForecast(
        int sampleSize,
        double mean,
        double stdDev,
        double coefficientOfVariation,
        double lowerBound,
        double upperBound,
        boolean widened) {
}
