package fr.pivot.collaboratif.exception;

/**
 * HTTP 422 Unprocessable Entity thrown when {@code POST .../start} is attempted on a meeting with
 * no agenda items at all (US12.2.1 AC-E3) — there is nothing to animate. Distinct from a 400/409:
 * the request is well-formed and the meeting's status does allow starting, but its current state
 * (an empty agenda, which US12.1.1 deliberately allows at creation time) makes the action
 * semantically impossible — the textbook case for 422 per RFC 4918 §11.2.
 */
public class MeetingEmptyAgendaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a meeting with no agenda items.
     */
    public MeetingEmptyAgendaException() {
        super("Meeting has no agenda items to animate");
    }
}
