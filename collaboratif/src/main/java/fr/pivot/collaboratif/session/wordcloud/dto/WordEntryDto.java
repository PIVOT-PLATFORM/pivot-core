package fr.pivot.collaboratif.session.wordcloud.dto;

/**
 * A single aggregated word/frequency pair, part of {@link WordAddedEvent} and the wordcloud read
 * model (US19.3.3).
 *
 * @param word      the normalized word
 * @param frequency current submission count for this word
 */
public record WordEntryDto(String word, int frequency) {
}
