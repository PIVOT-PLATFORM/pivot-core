package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lightweight reference to a capacity event, used to represent a parent or a child summary
 * within a {@link EventResponse} (US11.1.1/US11.3.1) without recursing into its own full
 * payload.
 *
 * @param id        the event's id
 * @param type      the event's kind
 * @param name      the event's name
 * @param startDate the event's start date
 * @param endDate   the event's end date
 */
public record EventRef(UUID id, CapacityEventType type, String name, LocalDate startDate, LocalDate endDate) {
}
