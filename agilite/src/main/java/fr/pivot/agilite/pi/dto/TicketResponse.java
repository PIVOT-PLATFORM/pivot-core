package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiTicket;
import fr.pivot.agilite.pi.PiTicketType;

import java.util.UUID;

/**
 * Response payload representing a single Program Board ticket (US50.3.1).
 *
 * @param id          unique identifier of the ticket
 * @param type        ticket type
 * @param title       ticket title
 * @param description ticket description, or {@code null}
 * @param teamId      target team, or {@code null} for the Train row
 * @param iterationId target iteration, or {@code null} for "Unplanned"
 * @param order       display order within its board cell
 */
public record TicketResponse(
        UUID id, PiTicketType type, String title, String description, UUID teamId, UUID iterationId, int order) {

    /**
     * Factory method that creates a {@link TicketResponse} from a {@link PiTicket} entity.
     *
     * @param ticket the ticket entity
     * @return a populated response record
     */
    public static TicketResponse from(final PiTicket ticket) {
        return new TicketResponse(
                ticket.getId(),
                ticket.getType(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getTeamId(),
                ticket.getIterationId(),
                ticket.getTicketOrder());
    }
}
