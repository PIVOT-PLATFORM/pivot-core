package fr.pivot.collaboratif.bingo.dto;

/**
 * STOMP {@code SEND} payload for {@code /app/collaboratif/bingo/{roomId}/mark} (US47.1.1,
 * AC-47.1.1-07). Carries only {@code cellIndex}/{@code marked} — no "I won" field is accepted or
 * ever trusted (AC-47.1.1-12, victory is exclusively server-computed).
 *
 * @param cellIndex the target cell's position — validated as an integer in {@code [0, 24]} by
 *                  {@code BingoWsController} before any further processing (AC-47.1.1-18)
 * @param marked    the requested marked state
 */
public record MarkCellRequest(Integer cellIndex, boolean marked) {
}
