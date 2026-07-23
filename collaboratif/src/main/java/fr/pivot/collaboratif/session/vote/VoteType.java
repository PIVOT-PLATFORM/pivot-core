package fr.pivot.collaboratif.session.vote;

/**
 * The structured-decision vote modes supported by the VOTE activity (US19.3.6).
 *
 * <p>{@link #FIST_TO_FIVE} — each participant rates a single proposal 0 (fist = veto) to 5, the
 * result is the average plus a consensus level and a veto alert. {@link #WEIGHTED} — each
 * participant distributes a fixed points budget across the options, the result is the points total
 * per option. {@link #MATRIX} — each participant scores every option against every weighted
 * criterion (a criteria×options grid); the result is each option's weighted mean score, ranked.
 */
public enum VoteType {
    FIST_TO_FIVE,
    WEIGHTED,
    MATRIX
}
