package fr.pivot.collaboratif.meeting.dto;

import fr.pivot.collaboratif.meeting.AgendaItem;

import java.util.UUID;

/**
 * Agenda item shape used within {@link MeetingLiveStateDto} (US12.2.1 AC-07) — like {@code
 * AgendaItemResponse} (US12.1.1) but additionally carrying the animation {@code itemStatus}
 * ({@code PENDING}/{@code CURRENT}/{@code DONE}) a resyncing participant needs to render agenda
 * progress without any STOMP history.
 *
 * @param id              item id
 * @param position        {@code 0}-based display order
 * @param title           item title
 * @param durationMinutes planned duration in minutes
 * @param type            the item's category
 * @param facilitator     optional facilitator display name, or {@code null}
 * @param itemStatus      animation status ({@code PENDING}/{@code CURRENT}/{@code DONE})
 */
public record AgendaItemStateDto(
        UUID id,
        int position,
        String title,
        Integer durationMinutes,
        String type,
        String facilitator,
        String itemStatus) {

    /**
     * Builds the state shape from a persisted {@link AgendaItem}.
     *
     * @param item the persisted entity
     * @return the state DTO
     */
    public static AgendaItemStateDto from(final AgendaItem item) {
        return new AgendaItemStateDto(
                item.getId(), item.getPosition(), item.getTitle(), item.getDurationMinutes(),
                item.getType().name(), item.getFacilitator(), item.getItemStatus().name());
    }
}
