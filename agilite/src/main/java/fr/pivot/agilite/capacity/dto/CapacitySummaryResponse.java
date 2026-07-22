package fr.pivot.agilite.capacity.dto;

/**
 * Response payload for an event's capacity summary (US11.1.2, extended by the F11.6 engine —
 * US11.6.1-5). {@code isProvisional} is {@code false} once the tenant/team has genuinely
 * configured holidays, maturity, or a focus-factor override — see {@code
 * fr.pivot.agilite.capacity.CapacitySummaryService}'s Javadoc.
 *
 * @param durationDays                 total calendar days in the event's period, inclusive
 * @param workingDays                  working days in the event's period (weekends and, once
 *                                     configured, tenant holidays excluded)
 * @param memberCount                  number of non-excluded contributing members (0 for a
 *                                     childless PI Planning/Increment event)
 * @param totalAbsenceDays             sum of contributing members' working-day absence overlap
 * @param netCapacityDays              the net capacity, in person-days
 * @param netCapacityPoints            {@code netCapacityDays * pointsPerDay}, or {@code null}
 * @param isProvisional                {@code false} once genuinely configured, see above
 * @param marginPercent                the effective engagement-recommendation margin (US11.6.4)
 * @param focusFactorPercent           the effective event-level focus factor (US11.6.2/US11.6.4)
 * @param maturitySource                {@code "TEAM_MATURITY"} or {@code "DEFAULT"} (US11.6.4)
 * @param forecastPoints                the velocity-based forecast (US11.6.3), or {@code null} —
 *                                      only computed for a {@code SPRINT} in preparation
 *                                      ({@code completedPoints} not yet set) with velocity history
 * @param engagementRecommendedPoints   {@code forecastPoints * (1 - marginPercent / 100)}, or
 *                                      {@code null} if {@code forecastPoints} is {@code null}
 */
public record CapacitySummaryResponse(
        int durationDays,
        int workingDays,
        int memberCount,
        int totalAbsenceDays,
        double netCapacityDays,
        Double netCapacityPoints,
        boolean isProvisional,
        int marginPercent,
        int focusFactorPercent,
        String maturitySource,
        Double forecastPoints,
        Double engagementRecommendedPoints) {
}
