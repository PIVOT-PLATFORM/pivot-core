package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityEventType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for creating a new capacity event (US11.1.1).
 *
 * <p>{@code type} is validated for enum membership, and {@code startDate < endDate}/{@code
 * parentEventId} depth-and-type rules are validated at the service layer ({@code
 * INVALID_EVENT_TYPE}/{@code INVALID_DATE_RANGE}/{@code INVALID_PARENT_EVENT}/{@code
 * MAX_DEPTH_EXCEEDED}) since they require a database lookup or cross-field logic a bean
 * validation annotation cannot express — mirroring {@code fr.pivot.agilite.pi.dto.CreateCycleRequest}.
 */
public record CreateEventRequest(
        @NotNull(message = "INVALID_EVENT_TYPE")
        CapacityEventType type,
        @NotNull(message = "INVALID_NAME")
        @Size(min = 1, max = 120, message = "INVALID_NAME")
        String name,
        @NotNull(message = "INVALID_TEAM_ID")
        Long teamId,
        @NotNull(message = "INVALID_DATE_RANGE")
        LocalDate startDate,
        @NotNull(message = "INVALID_DATE_RANGE")
        LocalDate endDate,
        UUID parentEventId) {
}
