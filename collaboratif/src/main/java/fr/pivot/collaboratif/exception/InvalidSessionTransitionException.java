package fr.pivot.collaboratif.exception;

/**
 * Thrown when a session lifecycle transition (start/pause/resume/end) is attempted from a status
 * that does not allow it (US19.1.2) — e.g. {@code DRAFT -> pause}, {@code PAUSED -> start}.
 * Mapped to HTTP 409 with code {@code INVALID_SESSION_TRANSITION}.
 */
public class InvalidSessionTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception describing the rejected transition.
     *
     * @param message a human-readable description of the invalid transition
     */
    public InvalidSessionTransitionException(final String message) {
        super(message);
    }
}
