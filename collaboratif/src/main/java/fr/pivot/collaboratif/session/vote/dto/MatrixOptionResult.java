package fr.pivot.collaboratif.session.vote.dto;

/**
 * A single MATRIX option's aggregated result (US19.3.6) — the weighted mean of its per-criterion
 * scores across all ballots.
 *
 * @param optionIndex the option's stable index in the session config's {@code options} array
 * @param label       the option's label
 * @param score       the option's weighted mean score (Σ criterion-weight × mean-cell-score)
 */
public record MatrixOptionResult(int optionIndex, String label, double score) {
}
