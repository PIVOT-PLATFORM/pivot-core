package fr.pivot.collaboratif.session.poll.dto;

import java.util.List;
import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} whenever a poll vote is cast/
 * changed, or results are hidden/shown (US19.3.2).
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param sessionId the session this event concerns
 * @param results   per-option tallies — {@code count}/{@code percent} absent from each entry when
 *                  results are hidden (see {@link PollOptionResult})
 */
public record PollUpdatedEvent(String type, UUID sessionId, List<PollOptionResult> results) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "POLL_UPDATED";

    /**
     * Creates the event.
     *
     * @param sessionId the session this event concerns
     * @param results   per-option tallies
     */
    public PollUpdatedEvent(final UUID sessionId, final List<PollOptionResult> results) {
        this(EVENT_TYPE, sessionId, results);
    }
}
