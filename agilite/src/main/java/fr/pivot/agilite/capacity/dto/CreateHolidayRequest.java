package fr.pivot.agilite.capacity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for adding a tenant holiday (US11.6.1).
 *
 * @param date  the holiday date
 * @param label human-readable label, 1-100 characters
 */
public record CreateHolidayRequest(
        @NotNull(message = "INVALID_HOLIDAY")
        LocalDate date,
        @NotNull(message = "INVALID_HOLIDAY")
        @Size(min = 1, max = 100, message = "INVALID_HOLIDAY")
        String label) {
}
