package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityMaturityLevel;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for updating a team's agile-maturity tier (US11.6.4).
 *
 * @param maturity the new maturity tier
 */
public record UpdateMaturityRequest(
        @NotNull(message = "INVALID_MATURITY")
        CapacityMaturityLevel maturity) {
}
