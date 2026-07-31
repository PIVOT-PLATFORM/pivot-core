package fr.pivot.collaboratif.bingo.exception;

/**
 * Thrown when a participant admitted as {@code SPECTATOR} (no persisted grid) attempts to mark a
 * cell (US47.1.1, AC-47.1.1-14) — surfaced to the emitting client only, on
 * {@code /user/queue/errors}, {@code {"code": "SPECTATOR_CANNOT_MARK"}}. Never broadcast.
 */
public class SpectatorCannotMarkException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a generic, non-leaking message. */
    public SpectatorCannotMarkException() {
        super("Spectators cannot mark cells");
    }
}
