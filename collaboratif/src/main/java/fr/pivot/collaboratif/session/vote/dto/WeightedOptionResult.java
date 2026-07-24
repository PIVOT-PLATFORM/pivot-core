package fr.pivot.collaboratif.session.vote.dto;

/**
 * A single WEIGHTED option's points total (US19.3.6).
 *
 * @param optionIndex the option's stable index in the session config's {@code options} array
 * @param label       the option's label
 * @param points      the total points allocated to this option across all ballots
 */
public record WeightedOptionResult(int optionIndex, String label, int points) {
}
