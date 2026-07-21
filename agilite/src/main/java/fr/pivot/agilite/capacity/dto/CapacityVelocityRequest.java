package fr.pivot.agilite.capacity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request body for upserting a sprint's velocity snapshot ({@code PATCH
 * /capacity/events/{id}/velocity}, F11.4).
 *
 * @param pointsEngages the sprint's committed points, must be {@code >= 0}
 * @param pointsLivres  the sprint's completed points, must be {@code >= 0}
 */
public record CapacityVelocityRequest(
        @NotNull(message = "INVALID_POINTS")
        @PositiveOrZero(message = "INVALID_POINTS")
        Double pointsEngages,

        @NotNull(message = "INVALID_POINTS")
        @PositiveOrZero(message = "INVALID_POINTS")
        Double pointsLivres) {
}
