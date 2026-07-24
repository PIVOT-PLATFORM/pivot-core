package fr.pivot.collaboratif.session.quiz.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when a participant answers the
 * current question (US19.3.1) — carries only the running answer count, never who answered or
 * whether they were right (revealed at {@link QuestionEndedEvent}).
 *
 * @param type          always {@link #EVENT_TYPE}
 * @param sessionId     the session this event concerns
 * @param questionIndex the question being answered
 * @param answerCount   the number of answers received so far for this question
 */
public record QuizAnsweredEvent(String type, UUID sessionId, int questionIndex, long answerCount) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "QUIZ_ANSWERED";

    /**
     * Creates the event.
     *
     * @param sessionId     the session this event concerns
     * @param questionIndex the question being answered
     * @param answerCount   the number of answers received so far
     */
    public QuizAnsweredEvent(final UUID sessionId, final int questionIndex, final long answerCount) {
        this(EVENT_TYPE, sessionId, questionIndex, answerCount);
    }
}
