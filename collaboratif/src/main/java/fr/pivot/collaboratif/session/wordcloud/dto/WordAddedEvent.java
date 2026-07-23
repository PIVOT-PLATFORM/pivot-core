package fr.pivot.collaboratif.session.wordcloud.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} whenever a word is submitted
 * (US19.3.3).
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param sessionId the session this event concerns
 * @param entry     the updated word/frequency pair
 */
public record WordAddedEvent(String type, UUID sessionId, WordEntryDto entry) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "WORD_ADDED";

    /**
     * Creates the event.
     *
     * @param sessionId the session this event concerns
     * @param entry     the updated word/frequency pair
     */
    public WordAddedEvent(final UUID sessionId, final WordEntryDto entry) {
        this(EVENT_TYPE, sessionId, entry);
    }
}
