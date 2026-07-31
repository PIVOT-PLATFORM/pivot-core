package fr.pivot.collaboratif.bingo.exception;

/**
 * Thrown when a join request's invite {@code code} is absent, blank, or not exactly 6 characters
 * (US47.1.1, AC-47.1.1-15) — mapped to HTTP 400 {@code {"code": "INVALID_CODE"}} by {@code
 * CollaboratifExceptionHandler}.
 */
public class InvalidCodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a generic, non-leaking message. */
    public InvalidCodeException() {
        super("Invalid invite code");
    }
}
