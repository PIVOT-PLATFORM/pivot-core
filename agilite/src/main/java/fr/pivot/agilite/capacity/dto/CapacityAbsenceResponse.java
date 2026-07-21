package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityAbsence;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Response payload for a {@link CapacityAbsence} (F11.2). Carries no motif/reason field — RGPD,
 * see {@link CapacityAbsence}'s Javadoc.
 *
 * @param id            unique identifier of the absence
 * @param eventMemberId the owning event member's identifier
 * @param startDate     the absence's first calendar day (inclusive)
 * @param endDate       the absence's last calendar day (inclusive)
 * @param fraction      {@code 1} (full day) or {@code 0.5} (half day)
 * @param source        {@code "MANUAL"} or an {@code "IMPORT:*"} connector key
 */
public record CapacityAbsenceResponse(
        UUID id,
        UUID eventMemberId,
        LocalDate startDate,
        LocalDate endDate,
        double fraction,
        String source) {

    /**
     * Factory method that creates a {@link CapacityAbsenceResponse} from a {@link
     * CapacityAbsence} entity.
     *
     * @param absence the absence entity
     * @return a populated response record
     */
    public static CapacityAbsenceResponse from(final CapacityAbsence absence) {
        return new CapacityAbsenceResponse(
                absence.getId(),
                absence.getEventMemberId(),
                absence.getStartDate(),
                absence.getEndDate(),
                absence.getFraction(),
                absence.getSource());
    }
}
