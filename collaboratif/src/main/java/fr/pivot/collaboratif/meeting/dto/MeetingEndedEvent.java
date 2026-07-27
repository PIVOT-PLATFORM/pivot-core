package fr.pivot.collaboratif.meeting.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/meeting/{id}} when a meeting ends (US12.2.1
 * AC-06) — either via {@code POST .../end} or by advancing past the last agenda item.
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param meetingId the meeting that ended
 * @param status    always {@code "ENDED"} — carried explicitly so a client need not special-case
 *                  this event's meaning against every other broadcast's implicit status
 * @param endedAt   the server instant the meeting ended at
 * @param serverTime the server instant this event was emitted at (equal to {@code endedAt} here,
 *                   kept distinct for the same reconciliation purpose as every other event's
 *                   {@code serverTime})
 */
public record MeetingEndedEvent(String type, UUID meetingId, String status, Instant endedAt, Instant serverTime) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "MEETING_ENDED";

    /** {@link #status}'s only possible value. */
    public static final String STATUS_ENDED = "ENDED";

    /**
     * Creates the event.
     *
     * @param meetingId the meeting that ended
     * @param endedAt   the server instant the meeting ended at
     */
    public MeetingEndedEvent(final UUID meetingId, final Instant endedAt) {
        this(EVENT_TYPE, meetingId, STATUS_ENDED, endedAt, endedAt);
    }
}
