package fr.pivot.collaboratif.session.vote.dto;

import fr.pivot.collaboratif.session.vote.VoteType;

import java.util.List;

/**
 * The result of a VOTE activity (US19.3.6) — a single shape covering both vote types. While the
 * vote is still open ({@code closed = false}), only {@code voteType} and {@code ballotCount} are
 * populated; every tally field stays {@code null}/empty so no partial result leaks before the
 * facilitator closes the vote.
 *
 * @param voteType        the session's vote mode
 * @param closed          whether the facilitator has closed the vote (tallies revealed)
 * @param ballotCount     number of ballots cast
 * @param average         FIST_TO_FIVE: mean rating (0-5), or {@code null}
 * @param consensusLevel  FIST_TO_FIVE: {@code STRONG} (≥4) / {@code MODERATE} (3-4) /
 *                        {@code WEAK} (&lt;3), or {@code null}
 * @param veto            FIST_TO_FIVE: {@code true} if any participant rated 0 (a veto), else
 *                        {@code false}
 * @param options         WEIGHTED: per-option points totals, or empty
 */
public record VoteResultsDto(
        VoteType voteType,
        boolean closed,
        long ballotCount,
        Double average,
        String consensusLevel,
        boolean veto,
        List<WeightedOptionResult> options) {

    /**
     * Builds the pre-close result — no tallies revealed.
     *
     * @param voteType    the session's vote mode
     * @param ballotCount number of ballots cast so far
     * @return an open-state result
     */
    public static VoteResultsDto open(final VoteType voteType, final long ballotCount) {
        return new VoteResultsDto(voteType, false, ballotCount, null, null, false, List.of());
    }
}
