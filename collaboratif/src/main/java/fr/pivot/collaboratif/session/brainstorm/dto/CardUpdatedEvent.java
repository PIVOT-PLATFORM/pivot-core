package fr.pivot.collaboratif.session.brainstorm.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when a card's content or category
 * changes (US19.3.4) — covers both an author edit and a facilitator re-categorization, carrying
 * the full updated card so clients replace their row wholesale.
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param sessionId the session this event concerns
 * @param card      the updated card
 */
public record CardUpdatedEvent(String type, UUID sessionId, BrainstormCardDto card) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "CARD_UPDATED";

    /**
     * Creates the event.
     *
     * @param sessionId the session this event concerns
     * @param card      the updated card
     */
    public CardUpdatedEvent(final UUID sessionId, final BrainstormCardDto card) {
        this(EVENT_TYPE, sessionId, card);
    }
}
