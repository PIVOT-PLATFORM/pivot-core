package fr.pivot.collaboratif.session.qa.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} whenever a question's upvote count
 * changes (US19.3.5). Carries only the affected question id and its new tally, so clients update a
 * single row and re-sort without a full list refetch.
 *
 * @param type       always {@link #EVENT_TYPE}
 * @param sessionId  the session this event concerns
 * @param questionId the upvoted question
 * @param upvotes    the new upvote count
 */
public record QuestionUpvotedEvent(String type, UUID sessionId, UUID questionId, long upvotes) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "QUESTION_UPVOTED";

    /**
     * Creates the event.
     *
     * @param sessionId  the session this event concerns
     * @param questionId the upvoted question
     * @param upvotes    the new upvote count
     */
    public QuestionUpvotedEvent(final UUID sessionId, final UUID questionId, final long upvotes) {
        this(EVENT_TYPE, sessionId, questionId, upvotes);
    }
}
