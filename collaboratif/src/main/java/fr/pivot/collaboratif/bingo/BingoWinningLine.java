package fr.pivot.collaboratif.bingo;

/**
 * A detected winning combination (US47.1.1, AC-47.1.1-10).
 *
 * @param kind  {@code ROW}, {@code COLUMN} or {@code DIAGONAL}
 * @param index 0..4 for {@code ROW}/{@code COLUMN}; 0 (main) or 1 (anti) for {@code DIAGONAL}
 */
public record BingoWinningLine(BingoLineKind kind, int index) {
}
