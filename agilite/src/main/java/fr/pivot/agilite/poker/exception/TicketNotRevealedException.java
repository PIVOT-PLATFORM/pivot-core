package fr.pivot.agilite.poker.exception;

import java.util.UUID;

/**
 * Thrown when the facilitator attempts to reset or finalize a planning poker ticket that is
 * still {@code VOTING} (US09.2.3) — both actions only make sense on a ticket that has already
 * been revealed at least once.
 *
 * <p>Mapped to HTTP 409 Conflict by {@code AgiliteExceptionHandler} with machine-readable code
 * {@code TICKET_NOT_REVEALED}.
 */
public class TicketNotRevealedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for the given ticket id.
     *
     * @param ticketId the ticket id that is not currently {@code REVEALED}
     */
    public TicketNotRevealedException(final UUID ticketId) {
        super("Ticket not revealed: " + ticketId);
    }
}
