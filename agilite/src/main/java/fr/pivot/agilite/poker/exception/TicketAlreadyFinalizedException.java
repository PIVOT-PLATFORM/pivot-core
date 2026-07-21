package fr.pivot.agilite.poker.exception;

import java.util.UUID;

/**
 * Thrown when the facilitator attempts to reset or finalize a planning poker ticket that already
 * has a persisted final estimate (US09.2.3) — finalization is a terminal, one-time transition
 * for a given ticket; neither action may be applied to it again afterward.
 *
 * <p>Mapped to HTTP 409 Conflict by {@code AgiliteExceptionHandler} with machine-readable code
 * {@code TICKET_ALREADY_FINALIZED}.
 */
public class TicketAlreadyFinalizedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for the given ticket id.
     *
     * @param ticketId the ticket id that has already been finalized
     */
    public TicketAlreadyFinalizedException(final UUID ticketId) {
        super("Ticket already finalized: " + ticketId);
    }
}
