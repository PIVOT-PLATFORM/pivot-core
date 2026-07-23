package fr.pivot.collaboratif.session.qa.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} whenever a question is submitted
 * (US19.3.5).
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param sessionId the session this event concerns
 * @param question  the newly added question
 */
public record QuestionAddedEvent(String type, UUID sessionId, QaQuestionDto question) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "QUESTION_ADDED";

    /**
     * Creates the event.
     *
     * @param sessionId the session this event concerns
     * @param question  the newly added question
     */
    public QuestionAddedEvent(final UUID sessionId, final QaQuestionDto question) {
        this(EVENT_TYPE, sessionId, question);
    }
}
