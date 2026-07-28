package fr.pivot.collaboratif.exception;

/**
 * Thrown when a meeting id does not resolve to an existing meeting accessible to the caller —
 * either it genuinely does not exist, or it belongs to another tenant (US12.2.1 AC-S1). These two
 * causes are deliberately never distinguished (404 anti-enumeration, never 403 — a caller must
 * never be able to tell "wrong tenant" apart from "does not exist"), mirroring {@link
 * SessionNotFoundException}'s identical posture for the Module Session domain.
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
