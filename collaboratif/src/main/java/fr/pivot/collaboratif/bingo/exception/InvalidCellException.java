package fr.pivot.collaboratif.bingo.exception;

/**
 * Thrown when a mark request's {@code cellIndex} is absent, not an integer, or outside
 * {@code [0, 24]} (US47.1.1, AC-47.1.1-18) — surfaced to the emitting client only, on
 * {@code /user/queue/errors}, {@code {"code": "INVALID_CELL"}}. Never persisted, never broadcast.
 */
public class InvalidCellException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a generic, non-leaking message. */
    public InvalidCellException() {
        super("Invalid cell index");
    }
}
