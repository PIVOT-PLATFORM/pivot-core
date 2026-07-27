package fr.pivot.collaboratif.meeting.dto;

import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/meeting/{id}} when the current agenda item
 * changes — manually via {@code POST .../agenda/next} (AC-03) or automatically on expiry when
 * {@code auto_advance} is enabled (AC-05).
 *
 * @param type                 always {@link #EVENT_TYPE}
 * @param meetingId            the meeting this change concerns
 * @param index                {@code 0}-based index of the new current item
 * @param total                total number of agenda items
 * @param currentAgendaItemId  the new current item's id
 */
public record AgendaItemChangedEvent(String type, UUID meetingId, int index, int total, UUID currentAgendaItemId) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "AGENDA_ITEM_CHANGED";

    /**
     * Creates the event.
     *
     * @param meetingId           the meeting this change concerns
     * @param index               {@code 0}-based index of the new current item
     * @param total               total number of agenda items
     * @param currentAgendaItemId the new current item's id
     */
    public AgendaItemChangedEvent(
            final UUID meetingId, final int index, final int total, final UUID currentAgendaItemId) {
        this(EVENT_TYPE, meetingId, index, total, currentAgendaItemId);
    }
}
