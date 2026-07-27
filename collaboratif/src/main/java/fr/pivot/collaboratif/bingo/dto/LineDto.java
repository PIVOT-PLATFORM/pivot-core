package fr.pivot.collaboratif.bingo.dto;

/**
 * Wire shape of a winning line, carried by {@link BingoEvent} (US47.1.1, AC-47.1.1-10).
 *
 * @param kind  {@code "ROW"}, {@code "COLUMN"} or {@code "DIAGONAL"}
 * @param index 0..4 for {@code ROW}/{@code COLUMN}; 0 (main) or 1 (anti) for {@code DIAGONAL}
 */
public record LineDto(String kind, int index) {
}
