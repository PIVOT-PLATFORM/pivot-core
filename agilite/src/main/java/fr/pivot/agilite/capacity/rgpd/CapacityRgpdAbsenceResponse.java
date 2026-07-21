package fr.pivot.agilite.capacity.rgpd;

import fr.pivot.agilite.capacity.CapacityAbsence;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One absence period exported for a data-subject access/portability request (US11.8.1, RGPD
 * Art. 15/20).
 *
 * <p>Carries only what {@link CapacityAbsence} itself carries — a period, a fraction, a source —
 * never a motif or health-related field, none of which exists anywhere in this schema (see {@link
 * CapacityAbsence}'s Javadoc). {@code eventId} is included so the person can trace the period back
 * to the capacity event it was recorded against.
 *
 * @param id        unique identifier of the absence
 * @param eventId   the owning capacity event's identifier
 * @param startDate the absence's first calendar day (inclusive)
 * @param endDate   the absence's last calendar day (inclusive)
 * @param fraction  {@code 1} (full day) or {@code 0.5} (half day)
 * @param source    {@code "MANUAL"} or an {@code "IMPORT:*"} connector key
 */
public record CapacityRgpdAbsenceResponse(
        UUID id,
        UUID eventId,
        LocalDate startDate,
        LocalDate endDate,
        double fraction,
        String source) {
}
