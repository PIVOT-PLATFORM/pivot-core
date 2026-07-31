package fr.pivot.collaboratif.bingo;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BingoWinDetector} — all 12 winning combinations (AC-47.1.1-10) plus the
 * no-win and malformed-input cases.
 */
class BingoWinDetectorTest {

    @Test
    void detect_emptyGrid_findsNoWin() {
        assertThat(BingoWinDetector.detect(new boolean[25])).isEmpty();
    }

    @Test
    void detect_almostCompleteRow_findsNoWin() {
        boolean[] marked = new boolean[25];
        marked[0] = true;
        marked[1] = true;
        marked[2] = true;
        marked[3] = true;
        // index 4 left unmarked — row 0 incomplete.

        assertThat(BingoWinDetector.detect(marked)).isEmpty();
    }

    @Test
    void detect_eachRow_isDetected() {
        for (int r = 0; r < 5; r++) {
            boolean[] marked = new boolean[25];
            for (int i = 0; i < 5; i++) {
                marked[r * 5 + i] = true;
            }
            Optional<BingoWinningLine> result = BingoWinDetector.detect(marked);
            assertThat(result).contains(new BingoWinningLine(BingoLineKind.ROW, r));
        }
    }

    @Test
    void detect_eachColumn_isDetected() {
        for (int c = 0; c < 5; c++) {
            boolean[] marked = new boolean[25];
            for (int i = 0; i < 5; i++) {
                marked[i * 5 + c] = true;
            }
            Optional<BingoWinningLine> result = BingoWinDetector.detect(marked);
            assertThat(result).contains(new BingoWinningLine(BingoLineKind.COLUMN, c));
        }
    }

    @Test
    void detect_mainDiagonal_isDetectedAsIndexZero() {
        boolean[] marked = new boolean[25];
        int[] indices = {0, 6, 12, 18, 24};
        for (int i : indices) {
            marked[i] = true;
        }

        assertThat(BingoWinDetector.detect(marked)).contains(new BingoWinningLine(BingoLineKind.DIAGONAL, 0));
    }

    @Test
    void detect_antiDiagonal_isDetectedAsIndexOne() {
        boolean[] marked = new boolean[25];
        int[] indices = {4, 8, 12, 16, 20};
        for (int i : indices) {
            marked[i] = true;
        }

        assertThat(BingoWinDetector.detect(marked)).contains(new BingoWinningLine(BingoLineKind.DIAGONAL, 1));
    }

    @Test
    void detect_fullyMarkedGrid_returnsTheFirstCheckedCombination() {
        boolean[] marked = new boolean[25];
        java.util.Arrays.fill(marked, true);

        // Rows are checked before columns/diagonals — row 0 is the first match.
        assertThat(BingoWinDetector.detect(marked)).contains(new BingoWinningLine(BingoLineKind.ROW, 0));
    }

    @Test
    void detect_wrongSizeArray_throws() {
        assertThatThrownBy(() -> BingoWinDetector.detect(new boolean[24]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
