package fr.pivot.agilite.standup.dto;

import java.util.UUID;

/**
 * {@code SESSION_ENDED} event broadcast to every participant on {@code
 * /topic/agilite/standup/{sessionId}} whenever a session reaches its terminal {@code DONE} status
 * — rotation past the last participant ({@code next}/auto-expiry, possibly via a {@code skip}),
 * or an early {@code POST .../end} (US10.1.2).
 *
 * @param type            always {@link #EVENT_TYPE} — discriminator for the shared session topic
 * @param sessionId       the session that ended
 * @param durationSeconds {@code endedAt - startedAt}, in seconds
 * @param participantCount total number of participants in the session
 */
public record SessionEndedEvent(String type, UUID sessionId, long durationSeconds, int participantCount) {

    /** Discriminator value for this event type — see {@link SessionStartedEvent}'s JavaDoc. */
    public static final String EVENT_TYPE = "SESSION_ENDED";

    /**
     * Builds the event with {@link #EVENT_TYPE} as its discriminator.
     *
     * @param sessionId        the session that ended
     * @param durationSeconds  {@code endedAt - startedAt}, in seconds
     * @param participantCount total number of participants in the session
     * @return the constructed event
     */
    public static SessionEndedEvent of(
            final UUID sessionId, final long durationSeconds, final int participantCount) {
        return new SessionEndedEvent(EVENT_TYPE, sessionId, durationSeconds, participantCount);
    }
}
