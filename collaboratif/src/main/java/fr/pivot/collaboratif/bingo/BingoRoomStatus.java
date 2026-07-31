package fr.pivot.collaboratif.bingo;

/**
 * Lifecycle status of a {@link BingoRoom} (US47.1.1). A room starts {@code OPEN} and transitions
 * exactly once, atomically, to {@code FINISHED} the moment the first bingo is detected
 * (AC-47.1.1-10/11) — never reversed, never re-opened.
 */
public enum BingoRoomStatus {
    OPEN,
    FINISHED
}
