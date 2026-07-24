package fr.pivot.collaboratif.session.qa.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when the facilitator marks a
 * question as answered (US19.3.5).
 *
 * @param type       always {@link #EVENT_TYPE}
 * @param sessionId  the session this event concerns
 * @param questionId the question marked answered
 */
public record QuestionAnsweredEvent(String type, UUID sessionId, UUID questionId) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "QUESTION_ANSWERED";

    /**
     * Creates the event.
     *
     * @param sessionId  the session this event concerns
     * @param questionId the question marked answered
     */
    public QuestionAnsweredEvent(final UUID sessionId, final UUID questionId) {
        this(EVENT_TYPE, sessionId, questionId);
    }
}
