package fr.pivot.collaboratif.bingo.exception;

/**
 * Thrown when a mark is attempted on a room that already transitioned to {@code FINISHED}
 * (US47.1.1, AC-47.1.1-11/19) — surfaced to the emitting client only, on
 * {@code /user/queue/errors}, {@code {"code": "ROOM_FINISHED"}}. Never broadcast.
 */
public class RoomFinishedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a generic, non-leaking message. */
    public RoomFinishedException() {
        super("Room already finished");
    }
}
