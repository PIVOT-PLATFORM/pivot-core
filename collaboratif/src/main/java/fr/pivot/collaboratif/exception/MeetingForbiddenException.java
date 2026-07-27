package fr.pivot.collaboratif.exception;

/**
 * HTTP 403 for a booking-flow action (confirm/adjust a slot) attempted by a caller who belongs to
 * the meeting's tenant but is not its organizer (US12.4.1 "Sécurité — autorisation validation").
 *
 * <p>Distinct from {@link MeetingNotFoundException} (404, anti-enumeration): tenant isolation
 * stays 404, but once a caller is genuinely in the right tenant, a non-organizer's action on a
 * meeting they can otherwise see is a genuine 403.
 */
public class MeetingForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     */
    public MeetingForbiddenException() {
        super("Only the meeting organizer may perform this action");
    }
}
