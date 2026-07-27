package fr.pivot.collaboratif.bingo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA entity backing a single cell of a {@link BingoGrid} (US47.1.1), table
 * {@code collaboratif.bingo_grid_cells}. {@code phraseText} is a denormalized snapshot captured
 * at grid-generation time (see the migration's Javadoc comment) — the source of truth for what
 * the participant actually sees and marks, independent of later edits to the shared phrase bank.
 */
@Entity
@Table(name = "bingo_grid_cells", schema = "collaboratif")
public class BingoGridCell {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "grid_id", nullable = false)
    private UUID gridId;

    @Column(name = "cell_index", nullable = false)
    private short cellIndex;

    @Column(name = "phrase_id", nullable = false)
    private UUID phraseId;

    @Column(name = "phrase_text", nullable = false, length = 200)
    private String phraseText;

    @Column(name = "marked", nullable = false)
    private boolean marked;

    /** No-argument constructor required by JPA. */
    protected BingoGridCell() {
    }

    /**
     * Creates a new, unmarked cell ready to persist.
     *
     * @param gridId     the owning grid's id
     * @param cellIndex  the cell's position (0..24)
     * @param phraseId   the phrase bank row this cell was drawn from
     * @param phraseText the phrase text snapshot
     */
    public BingoGridCell(final UUID gridId, final int cellIndex, final UUID phraseId, final String phraseText) {
        this.gridId = gridId;
        this.cellIndex = (short) cellIndex;
        this.phraseId = phraseId;
        this.phraseText = phraseText;
        this.marked = false;
    }

    /** @return database primary key */
    public UUID getId() {
        return id;
    }

    /** @return the owning grid's id */
    public UUID getGridId() {
        return gridId;
    }

    /** @return the cell's position (0..24) */
    public int getCellIndex() {
        return cellIndex;
    }

    /** @return the phrase bank row this cell was drawn from */
    public UUID getPhraseId() {
        return phraseId;
    }

    /** @return the phrase text snapshot shown to the participant */
    public String getPhraseText() {
        return phraseText;
    }

    /** @return whether this cell is currently marked */
    public boolean isMarked() {
        return marked;
    }

    /**
     * Updates this cell's marked state — idempotent by construction (AC-47.1.1-09): setting the
     * same value twice in a row leaves the persisted state, and therefore the derived
     * {@code markedCount}, unchanged.
     *
     * @param marked the new marked state
     */
    public void setMarked(final boolean marked) {
        this.marked = marked;
    }
}
