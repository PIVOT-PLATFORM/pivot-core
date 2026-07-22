package fr.pivot.agilite.capacity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for the idempotent daily burndown upsert (US11.4.2, {@code PUT
 * .../burndown/{date}}).
 *
 * @param pointsRemaining points remaining as of the path's {@code date}; must be non-negative
 */
public record UpsertBurndownEntryRequest(
        @NotNull(message = "INVALID_POINTS_REMAINING")
        @Min(value = 0, message = "INVALID_POINTS_REMAINING")
        Integer pointsRemaining) {
}
