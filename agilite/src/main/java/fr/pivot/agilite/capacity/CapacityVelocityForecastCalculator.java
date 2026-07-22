package fr.pivot.agilite.capacity;

import java.util.List;

/**
 * Pure, persistence-free computation of a team's net-person-day-weighted moving-average velocity
 * and confidence interval (US11.6.3).
 *
 * <p>Reference: PouetPouet's {@code summarizeHistory}/{@code avgVelocity} (({@code
 * apps/web/src/lib/capacity.ts})) — realized velocity per past sprint is {@code completedPoints /
 * netPersonDays}, averaged across the window weighted by each sprint's own {@code netPersonDays}.
 * This class adds the {@code [1, 10]}-bounded window and the {@code ±25%} coefficient-of-variation
 * confidence-interval classification, which PIVOT's own AC (US11.6.3) requires beyond the POC.
 * Deliberately decoupled from JPA/Spring, same posture as {@link CapacityCalculator}.
 */
public final class CapacityVelocityForecastCalculator {

    /** Coefficient-of-variation threshold above which the confidence interval widens (US11.6.3). */
    private static final double WIDE_CV_THRESHOLD = 0.25;

    private CapacityVelocityForecastCalculator() {
    }

    /**
     * One past sprint's realized velocity inputs.
     *
     * @param netPersonDays   the sprint's net capacity in person-days (weight for the average)
     * @param completedPoints the points actually delivered
     */
    public record SprintHistoryEntry(double netPersonDays, int completedPoints) {
    }

    /**
     * The computed forecast for a team.
     *
     * @param avgVelocity        the weighted-average realized velocity in points per net
     *                           person-day, or {@code null} if {@code basis} is {@code
     *                           "NO_HISTORY"}
     * @param confidenceInterval {@code "NARROW"} or {@code "WIDE"}, or {@code null} if {@code
     *                           basis} is {@code "NO_HISTORY"}
     * @param basis              {@code "HISTORY"} or {@code "NO_HISTORY"}
     */
    public record Forecast(Double avgVelocity, String confidenceInterval, String basis) {
    }

    /**
     * Computes the weighted-average velocity and confidence interval over the most recent {@code
     * window} entries of {@code history} (already ordered most-recent-first, already filtered to
     * only sprints with a recorded {@code completedPoints} — US11.6.3's "excluded from the
     * average, not counted as zero" rule is applied by the caller before this method is reached).
     *
     * @param history the team's completed-sprint history, most recent first
     * @param window  the number of most-recent entries to average over, {@code [1, 10]}
     * @return the computed forecast
     */
    public static Forecast forecast(final List<SprintHistoryEntry> history, final int window) {
        List<SprintHistoryEntry> windowed = history.stream().limit(window).toList();
        List<SprintHistoryEntry> weighable = windowed.stream().filter(entry -> entry.netPersonDays() > 0).toList();
        if (weighable.isEmpty()) {
            return new Forecast(null, null, "NO_HISTORY");
        }

        double totalDays = weighable.stream().mapToDouble(SprintHistoryEntry::netPersonDays).sum();
        double[] velocities = weighable.stream()
                .mapToDouble(entry -> entry.completedPoints() / entry.netPersonDays())
                .toArray();
        double weightedSum = 0;
        for (int i = 0; i < weighable.size(); i++) {
            weightedSum += velocities[i] * weighable.get(i).netPersonDays();
        }
        double avgVelocity = weightedSum / totalDays;

        double mean = average(velocities);
        double stdDev = Math.sqrt(average(variance(velocities, mean)));
        boolean wide = mean != 0 && Math.abs(stdDev / mean) > WIDE_CV_THRESHOLD;

        return new Forecast(round2(avgVelocity), wide ? "WIDE" : "NARROW", "HISTORY");
    }

    private static double average(final double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double[] variance(final double[] values, final double mean) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (values[i] - mean) * (values[i] - mean);
        }
        return result;
    }

    private static double round2(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
