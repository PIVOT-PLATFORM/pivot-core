package fr.pivot.agilite.standup.dto;

/**
 * {@code SESSION_STARTED} event broadcast to every participant on {@code
 * /topic/agilite/standup/{sessionId}} (US10.1.2) — fired once, right after an animator starts a
 * {@code PENDING} session. Carries the full session (participants included) so every subscriber
 * can render the running state without a follow-up {@code GET}.
 *
 * @param type    always {@link #EVENT_TYPE} — discriminator for the shared session topic
 * @param session the full session, already {@code RUNNING} with its first participant
 *                {@code SPEAKING}
 */
public record SessionStartedEvent(String type, StandupSessionResponse session) {

    /**
     * Discriminator value for this event type. Named {@code EVENT_TYPE} rather than {@code TYPE}
     * (SonarCloud java:S1845) to avoid any case-only clash with the record component
     * {@link #type()}.
     */
    public static final String EVENT_TYPE = "SESSION_STARTED";

    /**
     * Builds the event with {@link #EVENT_TYPE} as its discriminator.
     *
     * @param session the full, started session
     * @return the constructed event
     */
    public static SessionStartedEvent of(final StandupSessionResponse session) {
        return new SessionStartedEvent(EVENT_TYPE, session);
    }
}
