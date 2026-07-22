package fr.pivot.agilite.capacity.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for updating a capacity event's own fields (US11.1.1/US11.5.1/US11.6.2). All
 * fields optional — {@code null} means "leave unchanged".
 *
 * @param name               new name, or {@code null}
 * @param startDate          new start date, or {@code null}
 * @param endDate            new end date, or {@code null}
 * @param isIpIteration      new IP-iteration flag (US11.5.1), or {@code null}
 * @param focusFactorPercent new event-level focus-factor override in {@code [10, 100]}
 *                           (US11.6.2), or {@code null} to leave unchanged — clearing an existing
 *                           override is not supported by this field (no "explicit null" signal
 *                           available on an optional {@code Integer}); not needed by any AC.
 */
public record UpdateEventRequest(
        @Size(min = 1, max = 120, message = "INVALID_NAME")
        String name,
        LocalDate startDate,
        LocalDate endDate,
        Boolean isIpIteration,
        Integer focusFactorPercent) {
}
