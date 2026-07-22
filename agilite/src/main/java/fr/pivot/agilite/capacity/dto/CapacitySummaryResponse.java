package fr.pivot.agilite.capacity.dto;

/**
 * Response payload for an event's provisional capacity summary (US11.1.2). {@code isProvisional}
 * is always {@code true} — see {@code fr.pivot.agilite.capacity.CapacityCalculator}'s Javadoc.
 *
 * @param durationDays      total calendar days in the event's period, inclusive
 * @param workingDays       Monday-Friday days in the event's period, inclusive
 * @param memberCount       number of non-excluded contributing members (0 for a childless PI
 *                          Planning event)
 * @param totalAbsenceDays  sum of contributing members' working-day absence overlap
 * @param netCapacityDays   the provisional net capacity, in person-days
 * @param netCapacityPoints {@code netCapacityDays * pointsPerDay}, or {@code null}
 * @param isProvisional     always {@code true}
 */
public record CapacitySummaryResponse(
        int durationDays,
        int workingDays,
        int memberCount,
        int totalAbsenceDays,
        double netCapacityDays,
        Double netCapacityPoints,
        boolean isProvisional) {
}
