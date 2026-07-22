package fr.pivot.agilite.capacity.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One entry of a team's velocity history (US11.4.1) — a completed {@code SPRINT} event with its
 * committed/completed points.
 *
 * @param eventId         the sprint event's id
 * @param name            the sprint event's name
 * @param endDate         the sprint event's end date
 * @param committedPoints the points committed at sprint start, or {@code null}
 * @param completedPoints the points actually delivered, always non-null (filtered upstream)
 */
public record VelocityHistoryEntryResponse(
        UUID eventId, String name, LocalDate endDate, Integer committedPoints, Integer completedPoints) {
}
