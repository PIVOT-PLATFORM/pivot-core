package fr.pivot.agilite.capacity.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for adding a {@link fr.pivot.agilite.capacity.CapacityAbsence} to a capacity event
 * member (F11.2).
 *
 * <p>Carries no motif/reason field — RGPD, see {@code CapacityAbsence}'s Javadoc. {@code
 * endDate >= startDate}, the absence period falling within its event's window, and {@code
 * fraction} are all domain rules enforced in the service (not bean validation), so the specific
 * {@code INVALID_DATE_RANGE}/{@code ABSENCE_OUT_OF_RANGE}/{@code INVALID_FRACTION} codes can be
 * returned.
 *
 * @param startDate the absence's first calendar day (inclusive)
 * @param endDate   the absence's last calendar day (inclusive)
 * @param fraction  {@code 1} (full day) or {@code 0.5} (half day)
 * @param source    {@code "MANUAL"} or {@code "IMPORT:*"}, or {@code null} to default to
 *                  {@code "MANUAL"}
 */
public record CapacityAbsenceRequest(
        @NotNull(message = "INVALID_DATE_RANGE")
        LocalDate startDate,
        @NotNull(message = "INVALID_DATE_RANGE")
        LocalDate endDate,
        @NotNull(message = "INVALID_FRACTION")
        Double fraction,
        String source) {
}
