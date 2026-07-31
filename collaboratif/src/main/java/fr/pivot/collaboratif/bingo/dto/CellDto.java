package fr.pivot.collaboratif.bingo.dto;

/**
 * A single grid cell (US47.1.1, AC-47.1.1-04), returned only to the owning participant (create,
 * join, {@code GET .../grid}) — never broadcast on the shared room topic (SEC-04).
 *
 * @param cellIndex the cell's position (0..24)
 * @param phrase    the phrase text
 * @param marked    whether this cell is currently marked
 */
public record CellDto(int cellIndex, String phrase, boolean marked) {
}
