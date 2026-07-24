package fr.pivot.collaboratif.exception;

/**
 * Thrown when a session id does not resolve to an existing session accessible to the caller —
 * either it genuinely does not exist, belongs to another tenant, or the caller has no link to it
 * (not the creator, not a member of its optional team). These causes are deliberately never
 * distinguished (404 anti-enumeration, never 403 — US19.1.1/US19.1.2 §Sécurité).
 */
public class SessionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a session that could not be resolved for the caller.
     */
    public SessionNotFoundException() {
        super("Session not found");
    }
}
