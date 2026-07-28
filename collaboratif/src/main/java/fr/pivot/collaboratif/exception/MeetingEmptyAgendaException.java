package fr.pivot.collaboratif.exception;

/**
 * HTTP 409 Conflict (code {@code MEETING_HAS_NO_AGENDA}) thrown when {@code POST .../start} is
 * attempted on a meeting with no agenda items at all (US12.2.1 AC-E3, finalized Gate 1 exception
 * name {@code MeetingHasNoAgendaException}) — there is nothing to animate. The request is
 * well-formed; it is the meeting's current state (an empty agenda, which US12.1.1 deliberately
 * allows at creation time) that makes the action impossible right now — a lifecycle conflict, same
 * family as {@link MeetingConflictException}'s other cases, not a validation error.
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
