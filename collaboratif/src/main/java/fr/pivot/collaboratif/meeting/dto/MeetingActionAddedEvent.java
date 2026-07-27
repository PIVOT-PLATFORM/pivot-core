package fr.pivot.collaboratif.meeting.dto;

/**
 * STOMP broadcast on {@code /topic/collaboratif/meeting/{id}} when the animator captures an
 * action in-meeting (US12.2.1 AC-08) — carries the created action; the full detail (assignment,
 * due-date follow-up) is consumed later by US12.3.1/EN12.3's compte-rendu.
 *
 * @param type   always {@link #EVENT_TYPE}
 * @param action the newly created action
 */
public record MeetingActionAddedEvent(String type, MeetingActionDto action) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "MEETING_ACTION_ADDED";

    /**
     * Creates the event.
     *
     * @param action the newly created action
     */
    public MeetingActionAddedEvent(final MeetingActionDto action) {
        this(EVENT_TYPE, action);
    }
}
