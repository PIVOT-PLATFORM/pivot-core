package fr.pivot.collaboratif.session.quiz.dto;

import java.util.List;
import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when the facilitator ends a question
 * (US19.3.1) — reveals the correct option indices and the refreshed leaderboard.
 *
 * @param type           always {@link #EVENT_TYPE}
 * @param sessionId      the session this event concerns
 * @param questionIndex  the question that just ended
 * @param correctIndices the correct option indices, now revealed
 * @param leaderboard    the leaderboard after grading this question
 */
public record QuestionEndedEvent(
        String type,
        UUID sessionId,
        int questionIndex,
        List<Integer> correctIndices,
        List<LeaderboardEntry> leaderboard) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "QUESTION_ENDED";

    /**
     * Creates the event.
     *
     * @param sessionId      the session this event concerns
     * @param questionIndex  the question that just ended
     * @param correctIndices the correct option indices
     * @param leaderboard    the refreshed leaderboard
     */
    public QuestionEndedEvent(
            final UUID sessionId, final int questionIndex,
            final List<Integer> correctIndices, final List<LeaderboardEntry> leaderboard) {
        this(EVENT_TYPE, sessionId, questionIndex, correctIndices, leaderboard);
    }
}
