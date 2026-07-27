package fr.pivot.collaboratif.meeting.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/meeting/{id}} when a meeting ends (US12.2.1
 * AC-06) — either via {@code POST .../end} or by advancing past the last agenda item. Carries no
 * payload beyond the meeting id, mirroring {@code
 * fr.pivot.collaboratif.session.dto.SessionLifecycleEvent}'s minimal shape for terminal events.
 *
 * @param type      always {@link #EVENT_TYPE}
 * @param meetingId the meeting that ended
 */
public record MeetingEndedEvent(String type, UUID meetingId) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "MEETING_ENDED";

    /**
     * Creates the event.
     *
     * @param meetingId the meeting that ended
     */
    public MeetingEndedEvent(final UUID meetingId) {
        this(EVENT_TYPE, meetingId);
    }
}
