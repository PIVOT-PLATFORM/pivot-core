package fr.pivot.collaboratif.bingo.exception;

/**
 * Thrown when an invite code does not resolve to a currently joinable room — unknown code,
 * expired room, or already-{@code FINISHED} room, deliberately never distinguished
 * (US47.1.1, AC-47.1.1-16, anti-enumeration) — and when a {@code roomId}/accessToken pair
 * presented to {@code GET .../grid} has no valid grant (AC-47.1.1-20). Mapped to a generic HTTP
 * 404 by {@code CollaboratifExceptionHandler} — never 403, never a distinguishing message.
 */
public class BingoRoomNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a generic, non-leaking message. */
    public BingoRoomNotFoundException() {
        super("Room not found");
    }
}
