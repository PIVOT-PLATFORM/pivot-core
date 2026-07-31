package fr.pivot.collaboratif.bingo;

/**
 * The kind of a winning Bingo combination (US47.1.1, AC-47.1.1-10). {@code index} alongside this
 * enum is 0..4 for {@code ROW}/{@code COLUMN}; for {@code DIAGONAL}, {@code 0} is the main
 * diagonal (top-left to bottom-right) and {@code 1} is the anti-diagonal (top-right to
 * bottom-left).
 */
public enum BingoLineKind {
    ROW,
    COLUMN,
    DIAGONAL
}
