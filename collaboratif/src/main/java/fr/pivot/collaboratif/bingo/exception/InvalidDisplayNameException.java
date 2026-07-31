package fr.pivot.collaboratif.bingo.exception;

/**
 * Thrown when an anonymous join request's {@code displayName} is absent, blank, longer than 30
 * characters, or whitespace-only (US47.1.1, AC-47.1.1-17) — mapped to HTTP 400
 * {@code {"code": "INVALID_DISPLAY_NAME"}} by {@code CollaboratifExceptionHandler}.
 */
public class InvalidDisplayNameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a generic, non-leaking message. */
    public InvalidDisplayNameException() {
        super("Invalid display name");
    }
}
