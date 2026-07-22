package fr.pivot.agilite.standup.dto;

import java.util.UUID;

/**
 * {@code PARTICIPANT_CHANGED} event broadcast to every participant on {@code
 * /topic/agilite/standup/{sessionId}} whenever the speaking turn rotates to a new participant
 * (US10.1.2, {@code next} — manual or the US10.2.1 auto-expiry scheduler; a client cannot tell
 * the two apart, by design).
 *
 * @param type              always {@link #EVENT_TYPE} — discriminator for the shared session
 *                          topic
 * @param sessionId         the session whose turn rotated
 * @param currentParticipant the new current speaker
 */
public record ParticipantChangedEvent(String type, UUID sessionId, StandupParticipantResponse currentParticipant) {

    /** Discriminator value for this event type — see {@link SessionStartedEvent}'s JavaDoc. */
    public static final String EVENT_TYPE = "PARTICIPANT_CHANGED";

    /**
     * Builds the event with {@link #EVENT_TYPE} as its discriminator.
     *
     * @param sessionId          the session whose turn rotated
     * @param currentParticipant the new current speaker
     * @return the constructed event
     */
    public static ParticipantChangedEvent of(
            final UUID sessionId, final StandupParticipantResponse currentParticipant) {
        return new ParticipantChangedEvent(EVENT_TYPE, sessionId, currentParticipant);
    }
}
