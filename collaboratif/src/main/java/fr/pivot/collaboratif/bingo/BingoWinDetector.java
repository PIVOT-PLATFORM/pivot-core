package fr.pivot.collaboratif.bingo;

import java.util.Optional;

/**
 * Detects a completed Bingo combination from a 5x5 grid's marked state (US47.1.1, AC-47.1.1-10) —
 * the 12 winning combinations: 5 rows, 5 columns, the main diagonal and the anti-diagonal.
 *
 * <p>Cells are indexed 0..24, row-major: row {@code r} occupies indices {@code [5r, 5r+4]},
 * column {@code c} occupies indices {@code {c, c+5, c+10, c+15, c+20}}, the main diagonal is
 * {@code {0, 6, 12, 18, 24}} and the anti-diagonal is {@code {4, 8, 12, 16, 20}}.
 *
 * <p>Pure logic, no persistence — {@link BingoMarkService} is the sole caller, always against the
 * caller's own persisted grid (SEC-12, victory computed exclusively server-side from persisted
 * cells).
 */
public final class BingoWinDetector {

    /** Grid side length. */
    static final int SIZE = 5;

    private BingoWinDetector() {
    }

    /**
     * Checks all 12 combinations against the given marked state, in row/column/diagonal order,
     * returning the first one found fully marked.
     *
     * @param marked exactly 25 booleans, indexed 0..24 (row-major)
     * @return the first completed line found, or empty if none is complete
     * @throws IllegalArgumentException if {@code marked} does not have exactly 25 elements
     */
    public static Optional<BingoWinningLine> detect(final boolean[] marked) {
        if (marked.length != SIZE * SIZE) {
            throw new IllegalArgumentException("Expected 25 cells, got " + marked.length);
        }
        for (int r = 0; r < SIZE; r++) {
            if (allMarked(marked, rowIndices(r))) {
                return Optional.of(new BingoWinningLine(BingoLineKind.ROW, r));
            }
        }
        for (int c = 0; c < SIZE; c++) {
            if (allMarked(marked, columnIndices(c))) {
                return Optional.of(new BingoWinningLine(BingoLineKind.COLUMN, c));
            }
        }
        if (allMarked(marked, mainDiagonalIndices())) {
            return Optional.of(new BingoWinningLine(BingoLineKind.DIAGONAL, 0));
        }
        if (allMarked(marked, antiDiagonalIndices())) {
            return Optional.of(new BingoWinningLine(BingoLineKind.DIAGONAL, 1));
        }
        return Optional.empty();
    }

    private static boolean allMarked(final boolean[] marked, final int[] indices) {
        for (int index : indices) {
            if (!marked[index]) {
                return false;
            }
        }
        return true;
    }

    private static int[] rowIndices(final int r) {
        int[] indices = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            indices[i] = r * SIZE + i;
        }
        return indices;
    }

    private static int[] columnIndices(final int c) {
        int[] indices = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            indices[i] = i * SIZE + c;
        }
        return indices;
    }

    private static int[] mainDiagonalIndices() {
        int[] indices = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            indices[i] = i * SIZE + i;
        }
        return indices;
    }

    private static int[] antiDiagonalIndices() {
        int[] indices = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            indices[i] = i * SIZE + (SIZE - 1 - i);
        }
        return indices;
    }
}
