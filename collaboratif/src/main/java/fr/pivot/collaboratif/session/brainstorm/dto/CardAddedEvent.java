package fr.pivot.collaboratif.session.brainstorm.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when a card is added (US19.3.4).
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param sessionId the session this event concerns
 * @param card      the newly added card
 */
public record CardAddedEvent(String type, UUID sessionId, BrainstormCardDto card) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "CARD_ADDED";

    /**
     * Creates the event.
     *
     * @param sessionId the session this event concerns
     * @param card      the newly added card
     */
    public CardAddedEvent(final UUID sessionId, final BrainstormCardDto card) {
        this(EVENT_TYPE, sessionId, card);
    }
}
