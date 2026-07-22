package fr.pivot.agilite.pi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for creating a new PI cycle (US50.1.1).
 *
 * <p>{@code iterationCount}/{@code iterationWeeks} are validated as bounded (not merely present)
 * at the service layer ({@code INVALID_ITERATION_PARAMS}, [1,12]/[1,6]) since a bean-validation
 * annotation cannot express "default 5 if omitted, else validate the supplied value" in one
 * declaration — mirroring how {@code WheelEntryRequest.weight} defaults are handled.
 */
public record CreateCycleRequest(
        @NotBlank(message = "INVALID_NAME")
        @Size(min = 1, max = 120, message = "INVALID_NAME")
        String name,
        String artName,
        @NotNull(message = "INVALID_START_DATE")
        LocalDate startDate,
        Integer iterationCount,
        Integer iterationWeeks) {
}
