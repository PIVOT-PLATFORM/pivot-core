package fr.pivot.collaboratif.meeting.dto;

/**
 * STOMP broadcast on {@code /topic/collaboratif/meeting/{id}} when a meeting starts (US12.2.1
 * AC-01) — carries the full live state so every subscriber can render the first current item and
 * its timer without a follow-up {@code GET .../live} call.
 *
 * @param type  always {@link #EVENT_TYPE}
 * @param state the meeting's full live state at the moment it started
 */
public record MeetingStartedEvent(String type, MeetingLiveStateDto state) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "MEETING_STARTED";

    /**
     * Creates the event.
     *
     * @param state the meeting's full live state at the moment it started
     */
    public MeetingStartedEvent(final MeetingLiveStateDto state) {
        this(EVENT_TYPE, state);
    }
}
