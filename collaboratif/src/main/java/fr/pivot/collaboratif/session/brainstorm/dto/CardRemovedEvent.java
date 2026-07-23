package fr.pivot.collaboratif.session.brainstorm.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when a card is deleted (US19.3.4).
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param sessionId the session this event concerns
 * @param cardId    the removed card's id
 */
public record CardRemovedEvent(String type, UUID sessionId, UUID cardId) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "CARD_REMOVED";

    /**
     * Creates the event.
     *
     * @param sessionId the session this event concerns
     * @param cardId    the removed card's id
     */
    public CardRemovedEvent(final UUID sessionId, final UUID cardId) {
        this(EVENT_TYPE, sessionId, cardId);
    }
}
