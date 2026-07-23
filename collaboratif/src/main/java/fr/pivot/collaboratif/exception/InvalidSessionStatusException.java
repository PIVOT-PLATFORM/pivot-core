package fr.pivot.collaboratif.exception;

/**
 * Thrown when an activity-specific action (POLL vote, WORDCLOUD word submission, ...) is
 * attempted on a session that is not the expected type or not currently {@code LIVE}
 * (US19.3.2/US19.3.3). Mapped to HTTP 409 with code {@code INVALID_SESSION_STATUS}.
 */
public class InvalidSessionStatusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception describing the rejected action.
     *
     * @param message a human-readable description of why the session is not in the right state
     */
    public InvalidSessionStatusException(final String message) {
        super(message);
    }
}
