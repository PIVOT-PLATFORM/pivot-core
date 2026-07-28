package fr.pivot.collaboratif.exception;

/**
 * HTTP 404 when a {@code postitId} does not resolve to a spawn of the active POST-IT RUSH round
 * (US47.2.1) — unknown id, or one that belongs to a different round/session (cross-room
 * isolation).
 */
public class PostitNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a fixed, non-leaking message.
     */
    public PostitNotFoundException() {
        super("Post-it not found");
    }
}
