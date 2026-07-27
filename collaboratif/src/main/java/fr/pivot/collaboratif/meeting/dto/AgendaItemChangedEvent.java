package fr.pivot.collaboratif.meeting.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * STOMP broadcast on {@code /topic/collaboratif/meeting/{id}} when the current agenda item
 * changes — manually via {@code POST .../agenda/next} (AC-03) or automatically on expiry when
 * {@code auto_advance} is enabled (AC-05). {@code trigger}/{@code previousAgendaItemId} let a
 * client tell the two apart (e.g. to skip the "point suivant" click feedback on an automatic
 * transition) without the client having tracked the previous state itself.
 *
 * @param type                 always {@link #EVENT_TYPE}
 * @param meetingId            the meeting this change concerns
 * @param index                {@code 0}-based index of the new current item
 * @param total                total number of agenda items
 * @param currentAgendaItemId  the new current item's id
 * @param previousAgendaItemId the item that was current immediately before this change
 * @param trigger              what caused this change
 * @param serverTime           the server instant this change was made at
 */
public record AgendaItemChangedEvent(
        String type, UUID meetingId, int index, int total, UUID currentAgendaItemId,
        UUID previousAgendaItemId, Trigger trigger, Instant serverTime) {

    /** Event type discriminator. */
    public static final String EVENT_TYPE = "AGENDA_ITEM_CHANGED";

    /** What caused the agenda item to change. */
    public enum Trigger {
        /** The animator called {@code POST .../agenda/next} (AC-03). */
        MANUAL,
        /** The current item's timer expired with {@code auto_advance} enabled (AC-05). */
        TIMER_EXPIRED
    }

    /**
     * Creates the event.
     *
     * @param meetingId            the meeting this change concerns
     * @param index                {@code 0}-based index of the new current item
     * @param total                total number of agenda items
     * @param currentAgendaItemId  the new current item's id
     * @param previousAgendaItemId the item that was current immediately before this change
     * @param trigger              what caused this change
     * @param serverTime           the server instant this change was made at
     */
    public AgendaItemChangedEvent(
            final UUID meetingId, final int index, final int total, final UUID currentAgendaItemId,
            final UUID previousAgendaItemId, final Trigger trigger, final Instant serverTime) {
        this(EVENT_TYPE, meetingId, index, total, currentAgendaItemId, previousAgendaItemId, trigger, serverTime);
    }
}
