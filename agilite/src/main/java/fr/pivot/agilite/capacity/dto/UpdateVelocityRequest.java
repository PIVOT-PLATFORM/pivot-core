package fr.pivot.agilite.capacity.dto;

/**
 * Request body for recording a {@code SPRINT} event's velocity (US11.4.1). Both fields
 * independently optional — {@code committedPoints} at sprint start, {@code completedPoints} at
 * sprint end, without needing to resupply the other.
 *
 * @param committedPoints new committed points, or {@code null} to leave unchanged
 * @param completedPoints new completed points, or {@code null} to leave unchanged
 */
public record UpdateVelocityRequest(Integer committedPoints, Integer completedPoints) {
}
