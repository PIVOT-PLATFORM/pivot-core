package fr.pivot.collaboratif.bingo.ws;

/**
 * Error payload sent to a single emitting client's {@code /user/queue/errors} destination
 * (US47.1.1, AC-47.1.1-14/18/19) — never broadcast to the room topic.
 *
 * @param error human-readable rejection reason (WS access denial)
 * @param code  machine-readable code ({@code SPECTATOR_CANNOT_MARK}, {@code INVALID_CELL},
 *              {@code ROOM_FINISHED}), or {@code null} for a plain access-denial notification
 */
public record BingoErrorPayload(String error, String code) {

    /**
     * Builds a plain access-denial payload with no machine-readable code.
     *
     * @param error human-readable rejection reason
     */
    public BingoErrorPayload(final String error) {
        this(error, null);
    }
}
