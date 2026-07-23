package fr.pivot.collaboratif.session.wordcloud.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} whenever a facilitator removes a
 * word (US19.3.3).
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param sessionId the session this event concerns
 * @param word      the removed, normalized word
 */
public record WordRemovedEvent(String type, UUID sessionId, String word) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "WORD_REMOVED";

    /**
     * Creates the event.
     *
     * @param sessionId the session this event concerns
     * @param word      the removed, normalized word
     */
    public WordRemovedEvent(final UUID sessionId, final String word) {
        this(EVENT_TYPE, sessionId, word);
    }
}
