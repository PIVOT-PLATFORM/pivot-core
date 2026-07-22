package fr.pivot.agilite.capacity.dto;

/**
 * Response payload for a team's average velocity and suggested next-sprint capacity (US11.4.1).
 *
 * @param averageVelocity  simple average of {@code completedPoints} over the last {@code count}
 *                          sprints that have one set, or {@code null} if none do
 * @param suggestedCapacity {@code averageVelocity * factor}, or {@code null} if {@code
 *                          averageVelocity} is {@code null}
 */
public record VelocityAverageResponse(Double averageVelocity, Double suggestedCapacity) {
}
