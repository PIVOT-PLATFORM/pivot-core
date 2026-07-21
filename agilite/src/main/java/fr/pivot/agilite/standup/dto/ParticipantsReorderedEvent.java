package fr.pivot.agilite.standup.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code PARTICIPANTS_REORDERED} event broadcast to every participant on {@code
 * /topic/agilite/standup/{sessionId}} whenever the facilitator reorders the still-{@code WAITING}
 * tail of the queue (US10.2.2). Carries the session's full, updated participant list (already
 * {@code SPEAKING}/{@code DONE}/{@code SKIPPED} participants included, unchanged) so clients never
 * need to reconcile a partial reorder against their own local state.
 *
 * @param type         always {@link #EVENT_TYPE} — discriminator for the shared session topic
 * @param sessionId    the session whose participants were reordered
 * @param participants the session's full, updated participant list, in the new order
 */
public record ParticipantsReorderedEvent(
        String type, UUID sessionId, List<StandupParticipantResponse> participants) {

    /** Discriminator value for this event type — see {@link SessionStartedEvent}'s JavaDoc. */
    public static final String EVENT_TYPE = "PARTICIPANTS_REORDERED";

    /**
     * Builds the event with {@link #EVENT_TYPE} as its discriminator.
     *
     * @param sessionId    the session whose participants were reordered
     * @param participants the session's full, updated participant list, in the new order
     * @return the constructed event
     */
    public static ParticipantsReorderedEvent of(
            final UUID sessionId, final List<StandupParticipantResponse> participants) {
        return new ParticipantsReorderedEvent(EVENT_TYPE, sessionId, participants);
    }
}
