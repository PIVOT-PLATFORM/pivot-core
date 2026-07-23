package fr.pivot.collaboratif.session.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/session/{id}} when a participant joins
 * (US19.2.1).
 *
 * @param type          always {@link #EVENT_TYPE}
 * @param participantId the joining participant's id
 * @param displayName   the joining participant's display name (already escaped for display)
 */
public record ParticipantJoinedEvent(String type, UUID participantId, String displayName) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "PARTICIPANT_JOINED";

    /**
     * Creates the event.
     *
     * @param participantId the joining participant's id
     * @param displayName   the joining participant's display name
     */
    public ParticipantJoinedEvent(final UUID participantId, final String displayName) {
        this(EVENT_TYPE, participantId, displayName);
    }
}
