package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityEvent;
import fr.pivot.agilite.capacity.CapacityEventStatus;
import fr.pivot.agilite.capacity.CapacityEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Compact listing shape for a capacity event's direct children (E11 — F11.3 hierarchy),
 * returned by {@code GET /events/{piId}/children} — deliberately smaller than {@link
 * CapacityEventResponse} since a PI's children list is typically rendered as a summary row, not
 * a full detail view.
 *
 * @param id        unique identifier of the child event
 * @param type      the kind of event (typically {@link CapacityEventType#SPRINT})
 * @param status    the child event's current lifecycle status
 * @param name      the child event's display name
 * @param startDate the child event's first calendar day (inclusive)
 * @param endDate   the child event's last calendar day (inclusive)
 */
public record CapacityEventChildResponse(
        UUID id,
        CapacityEventType type,
        CapacityEventStatus status,
        String name,
        LocalDate startDate,
        LocalDate endDate) {

    /**
     * Factory method that creates a {@link CapacityEventChildResponse} from a {@link
     * CapacityEvent} entity.
     *
     * @param event the child capacity event entity
     * @return a populated response record
     */
    public static CapacityEventChildResponse from(final CapacityEvent event) {
        return new CapacityEventChildResponse(
                event.getId(),
                event.getType(),
                event.getStatus(),
                event.getName(),
                event.getStartDate(),
                event.getEndDate());
    }
}
