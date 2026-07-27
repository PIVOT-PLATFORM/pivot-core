package fr.pivot.collaboratif.bingo.dto;

import java.util.List;

/**
 * A participant's own grid (US47.1.1, AC-47.1.1-04/05) — {@code null} for a spectator
 * (AC-47.1.1-13).
 *
 * @param cells exactly 25 cells
 */
public record GridDto(List<CellDto> cells) {
}
