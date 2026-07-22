package fr.pivot.agilite.standup.dto;

import java.util.UUID;

/**
 * {@code TIMER_EXTENDED} event broadcast to every participant on {@code
 * /topic/agilite/standup/{sessionId}} whenever the facilitator extends the current speaker's time
 * (US10.2.2). Every client recalculates its visual timer (US10.2.1) using the new base
 * {@code timePerPersonSeconds + extraSeconds}.
 *
 * @param type          always {@link #EVENT_TYPE} — discriminator for the shared session topic
 * @param sessionId     the session whose current speaker's time was extended
 * @param participantId the extended participant's id
 * @param extraSeconds  the participant's new cumulative extra seconds (already includes this
 *                      extension)
 */
public record TimerExtendedEvent(String type, UUID sessionId, UUID participantId, int extraSeconds) {

    /** Discriminator value for this event type — see {@link SessionStartedEvent}'s JavaDoc. */
    public static final String EVENT_TYPE = "TIMER_EXTENDED";

    /**
     * Builds the event with {@link #EVENT_TYPE} as its discriminator.
     *
     * @param sessionId     the session whose current speaker's time was extended
     * @param participantId the extended participant's id
     * @param extraSeconds  the participant's new cumulative extra seconds
     * @return the constructed event
     */
    public static TimerExtendedEvent of(
            final UUID sessionId, final UUID participantId, final int extraSeconds) {
        return new TimerExtendedEvent(EVENT_TYPE, sessionId, participantId, extraSeconds);
    }
}
