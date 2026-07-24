package fr.pivot.collaboratif.session.vote.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when a ballot is cast (US19.3.6) —
 * carries only the running ballot count, never any ballot value, so nothing about how people voted
 * leaks before the facilitator closes the vote.
 *
 * @param type        always {@link #EVENT_TYPE}
 * @param sessionId   the session this event concerns
 * @param ballotCount the number of ballots cast so far
 */
public record VoteSubmittedEvent(String type, UUID sessionId, long ballotCount) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "VOTE_SUBMITTED";

    /**
     * Creates the event.
     *
     * @param sessionId   the session this event concerns
     * @param ballotCount the number of ballots cast so far
     */
    public VoteSubmittedEvent(final UUID sessionId, final long ballotCount) {
        this(EVENT_TYPE, sessionId, ballotCount);
    }
}
