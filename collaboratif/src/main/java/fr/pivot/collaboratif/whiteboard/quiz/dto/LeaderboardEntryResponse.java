package fr.pivot.collaboratif.whiteboard.quiz.dto;

/**
 * Wire representation of a single leaderboard entry — a participant's cumulative score and rank
 * within a quiz session, broadcast on {@code quiz:updated} (after reveal) and
 * {@code quiz:session:closed} (final leaderboard).
 *
 * <p>Score and rank are computed by the caller (service layer, lot C1) from the session's {@link
 * fr.pivot.collaboratif.whiteboard.quiz.Answer} rows against each question's correct choice(s);
 * this record performs no aggregation itself.
 *
 * @param userId the participant's {@code public.users.id} as a string
 * @param score  the participant's cumulative score (number of correct answers, MVP scoring)
 * @param rank   the participant's 1-based rank within the leaderboard (ties share a rank per the
 *               caller's tie-breaking policy)
 */
public record LeaderboardEntryResponse(String userId, int score, int rank) {

    /**
     * Builds a {@link LeaderboardEntryResponse} from an already-computed score and rank.
     *
     * @param userId the participant's {@code public.users.id}
     * @param score  the participant's cumulative score
     * @param rank   the participant's 1-based rank
     * @return the corresponding {@link LeaderboardEntryResponse}
     */
    public static LeaderboardEntryResponse of(final Long userId, final int score, final int rank) {
        return new LeaderboardEntryResponse(String.valueOf(userId), score, rank);
    }
}
