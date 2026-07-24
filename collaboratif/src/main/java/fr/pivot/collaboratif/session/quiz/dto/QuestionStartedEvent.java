package fr.pivot.collaboratif.session.quiz.dto;

import java.util.List;
import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when the facilitator starts a
 * question (US19.3.1) — deliberately carries the options but <strong>not</strong> the correct
 * answer, which is revealed only in {@link QuestionEndedEvent}.
 *
 * @param type            always {@link #EVENT_TYPE}
 * @param sessionId       the session this event concerns
 * @param questionIndex   the 0-based index of the question now on screen
 * @param totalQuestions  the total number of questions in the quiz
 * @param text            the question text
 * @param options         the answer options
 * @param durationSeconds the answer window in seconds (display countdown; the server is the clock)
 */
public record QuestionStartedEvent(
        String type,
        UUID sessionId,
        int questionIndex,
        int totalQuestions,
        String text,
        List<String> options,
        int durationSeconds) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "QUESTION_STARTED";

    /**
     * Creates the event.
     *
     * @param sessionId       the session this event concerns
     * @param questionIndex   the 0-based index of the question now on screen
     * @param totalQuestions  the total number of questions
     * @param text            the question text
     * @param options         the answer options
     * @param durationSeconds the answer window in seconds
     */
    public QuestionStartedEvent(
            final UUID sessionId, final int questionIndex, final int totalQuestions,
            final String text, final List<String> options, final int durationSeconds) {
        this(EVENT_TYPE, sessionId, questionIndex, totalQuestions, text, options, durationSeconds);
    }
}
