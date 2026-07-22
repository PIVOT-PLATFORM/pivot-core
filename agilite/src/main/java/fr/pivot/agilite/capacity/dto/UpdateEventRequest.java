package fr.pivot.agilite.capacity.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for updating a capacity event's own fields (US11.1.1). All fields optional —
 * {@code null} means "leave unchanged".
 */
public record UpdateEventRequest(
        @Size(min = 1, max = 120, message = "INVALID_NAME")
        String name,
        LocalDate startDate,
        LocalDate endDate) {
}
