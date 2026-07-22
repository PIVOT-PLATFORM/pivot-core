package fr.pivot.agilite.capacity.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Response payload for a single tenant holiday (US11.6.1).
 *
 * @param id    the holiday's id
 * @param date  the holiday date
 * @param label the human-readable label
 */
public record HolidayResponse(UUID id, LocalDate date, String label) {
}
