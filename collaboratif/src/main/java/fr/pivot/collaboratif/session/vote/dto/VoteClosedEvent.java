package fr.pivot.collaboratif.session.vote.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when the facilitator closes the vote
 * (US19.3.6) — carries the full, now-revealed results.
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param sessionId the session this event concerns
 * @param results   the final tallied results
 */
public record VoteClosedEvent(String type, UUID sessionId, VoteResultsDto results) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "VOTE_CLOSED";

    /**
     * Creates the event.
     *
     * @param sessionId the session this event concerns
     * @param results   the final tallied results
     */
    public VoteClosedEvent(final UUID sessionId, final VoteResultsDto results) {
        this(EVENT_TYPE, sessionId, results);
    }
}
