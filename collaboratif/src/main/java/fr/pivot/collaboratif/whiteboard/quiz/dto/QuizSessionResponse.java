package fr.pivot.collaboratif.whiteboard.quiz.dto;

import fr.pivot.collaboratif.whiteboard.quiz.QuizSession;

import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a {@link QuizSession} plus the current question and leaderboard
 * (calques {@code VoteSessionResponse}).
 *
 * <p>This is the shape sent on every broadcast ({@code quiz:session:started}, {@code
 * quiz:updated}, {@code quiz:session:closed}) and REST read ({@code GET .../quiz/current|last}) —
 * see §5 of {@code QUIZ-ACTIVITY-DESIGN.md}.
 *
 * <p>⚠️ {@code tenantId} is deliberately never exposed here (same rule as {@code
 * VoteSessionResponse}) — tenant scoping is a server-side concern only. {@code currentQuestion}
 * carries the masking already applied by the caller ({@link QuestionResponse}/{@link
 * ChoiceResponse} vs {@link ChoiceRevealResponse}); this record itself makes no masking decision.
 *
 * @param id                   the session UUID as a string
 * @param boardId              the owning board UUID as a string
 * @param status               the lifecycle status name ({@code ACTIVE}/{@code CLOSED})
 * @param currentQuestionIndex the 0-based index of the question in play, or {@code null} if the
 *                             quiz has not started
 * @param currentQuestion      the current question (masked or demasked per its state), or
 *                             {@code null} if the quiz has not started or is closed with no
 *                             question in play
 * @param leaderboard          the cumulative or final leaderboard entries
 * @param createdAt            the session start instant (ISO-8601)
 * @param closedAt             the session close instant (ISO-8601), or {@code null} while active
 */
public record QuizSessionResponse(
        String id,
        String boardId,
        String status,
        Integer currentQuestionIndex,
        QuestionResponse currentQuestion,
        List<LeaderboardEntryResponse> leaderboard,
        String createdAt,
        String closedAt) {

    /**
     * Builds a {@link QuizSessionResponse} from a session, its already-masked current question and
     * its already-computed leaderboard.
     *
     * @param session         the persisted session
     * @param currentQuestion the current question DTO (masked/demasked per its state), or
     *                        {@code null} if none is in play
     * @param leaderboard     the leaderboard entries (may be empty)
     * @return the corresponding {@link QuizSessionResponse}
     */
    public static QuizSessionResponse of(
            final QuizSession session,
            final QuestionResponse currentQuestion,
            final List<LeaderboardEntryResponse> leaderboard) {
        return new QuizSessionResponse(
                session.getId().toString(),
                session.getBoardId().toString(),
                session.getStatus().name(),
                session.getCurrentQuestionIndex(),
                currentQuestion,
                leaderboard,
                toIso(session.getCreatedAt()),
                toIso(session.getClosedAt()));
    }

    /**
     * Renders an instant to its ISO-8601 string, or {@code null} when the instant is absent.
     *
     * @param instant the instant, or {@code null}
     * @return the ISO-8601 string, or {@code null}
     */
    private static String toIso(final Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
