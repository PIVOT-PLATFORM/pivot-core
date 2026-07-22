package fr.pivot.agilite.capacity.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Response payload for a single absence (US11.2.2). Deliberately carries no reason/category
 * field — see {@code fr.pivot.agilite.capacity.CapacityAbsence}'s Javadoc.
 *
 * @param id        the absence's id
 * @param dateDebut absence start date, inclusive
 * @param dateFin   absence end date, inclusive
 */
public record AbsenceResponse(UUID id, LocalDate dateDebut, LocalDate dateFin) {
}
