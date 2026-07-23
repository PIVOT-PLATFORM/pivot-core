package fr.pivot.collaboratif.session.dto;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when a session starts (US19.1.2).
 *
 * @param type    always {@link #EVENT_TYPE}
 * @param session the full, started session
 */
public record SessionStartedEvent(String type, SessionResponse session) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "SESSION_STARTED";

    /**
     * Creates the event.
     *
     * @param session the full, started session
     */
    public SessionStartedEvent(final SessionResponse session) {
        this(EVENT_TYPE, session);
    }
}
