package fr.pivot.collaboratif.bingo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link BingoGridCell} (US47.1.1).
 */
public interface BingoGridCellRepository extends JpaRepository<BingoGridCell, UUID> {

    /**
     * @param gridId the owning grid's id
     * @return the grid's 25 cells, in no particular guaranteed order (callers sort by {@link
     *     BingoGridCell#getCellIndex()} when order matters)
     */
    List<BingoGridCell> findByGridId(UUID gridId);

    /**
     * Resolves the single cell targeted by a mark request — the persistence-level authorization
     * boundary that makes a mark request only ever able to touch its own grid's cell (SEC-02).
     *
     * @param gridId    the owning grid's id
     * @param cellIndex the cell's position (0..24)
     * @return the matching cell, if the grid has one at this index
     */
    Optional<BingoGridCell> findByGridIdAndCellIndex(UUID gridId, int cellIndex);

    /**
     * @param gridId the owning grid's id
     * @return how many of this grid's cells are currently marked
     */
    long countByGridIdAndMarkedTrue(UUID gridId);
}
