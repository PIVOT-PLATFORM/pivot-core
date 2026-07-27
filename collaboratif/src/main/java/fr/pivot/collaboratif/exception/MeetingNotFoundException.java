package fr.pivot.collaboratif.exception;

/**
 * Thrown when a meeting id does not resolve to an existing meeting accessible to the caller —
 * either it genuinely does not exist or it belongs to another tenant. These causes are
 * deliberately never distinguished (404 anti-enumeration, never a different code — US12.4.1
 * "Sécurité — isolation tenant").
 */
public class MeetingNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a meeting that could not be resolved for the caller.
     */
    public MeetingNotFoundException() {
        super("Meeting not found");
    }
}
