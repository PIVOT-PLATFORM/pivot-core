package fr.pivot.agilite.capacity.dto;

/**
 * Response payload for a team's moving-average velocity forecast (US11.6.3).
 *
 * @param avgVelocity        the weighted-average realized velocity in points per net person-day,
 *                           or {@code null} if {@code basis} is {@code "NO_HISTORY"}
 * @param confidenceInterval {@code "NARROW"} or {@code "WIDE"}, or {@code null} if {@code basis}
 *                           is {@code "NO_HISTORY"}
 * @param basis              {@code "HISTORY"} or {@code "NO_HISTORY"}
 */
public record VelocityForecastResponse(Double avgVelocity, String confidenceInterval, String basis) {
}
