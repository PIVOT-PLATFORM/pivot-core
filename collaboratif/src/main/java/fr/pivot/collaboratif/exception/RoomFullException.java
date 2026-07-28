package fr.pivot.collaboratif.exception;

/**
 * HTTP 409 {@code ROOM_FULL} when a POST-IT RUSH room has reached its hard participant capacity
 * and the join request did not explicitly accept the spectator fallback (US47.2.1). Deliberately
 * distinct from a brutal block: the response offers a spectator fallback the caller can accept by
 * retrying the join with {@code spectator: true} — never a Mentimeter-style quota lockout.
 */
public class RoomFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a fixed, descriptive message.
     */
    public RoomFullException() {
        super("Room is at hard capacity — retry with spectator fallback accepted");
    }
}
