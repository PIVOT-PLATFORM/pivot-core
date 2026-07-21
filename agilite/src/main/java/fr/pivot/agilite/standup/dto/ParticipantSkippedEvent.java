package fr.pivot.agilite.standup.dto;

import java.util.UUID;

/**
 * {@code PARTICIPANT_SKIPPED} event broadcast to every participant on {@code
 * /topic/agilite/standup/{sessionId}} whenever the facilitator skips the current speaker
 * (US10.2.2) — same payload shape as {@link ParticipantChangedEvent} ({@code nouveau participant
 * courant, ou fin de session} per the AC), distinguished only by {@link #type()} so clients can
 * show a different transient notification ("skipped" vs. a normal turn change) without any
 * additional payload parsing.
 *
 * @param type              always {@link #EVENT_TYPE} — discriminator for the shared session
 *                          topic
 * @param sessionId         the session whose turn rotated
 * @param currentParticipant the new current speaker, or {@code null} if the skip ended the
 *                          session (a separate {@code SESSION_ENDED} event is also broadcast in
 *                          that case, same as a normal {@code next})
 */
public record ParticipantSkippedEvent(String type, UUID sessionId, StandupParticipantResponse currentParticipant) {

    /** Discriminator value for this event type — see {@link SessionStartedEvent}'s JavaDoc. */
    public static final String EVENT_TYPE = "PARTICIPANT_SKIPPED";

    /**
     * Builds the event with {@link #EVENT_TYPE} as its discriminator.
     *
     * @param sessionId          the session whose turn rotated
     * @param currentParticipant the new current speaker, or {@code null}
     * @return the constructed event
     */
    public static ParticipantSkippedEvent of(
            final UUID sessionId, final StandupParticipantResponse currentParticipant) {
        return new ParticipantSkippedEvent(EVENT_TYPE, sessionId, currentParticipant);
    }
}
