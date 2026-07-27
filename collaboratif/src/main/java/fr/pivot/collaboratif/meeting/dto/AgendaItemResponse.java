package fr.pivot.collaboratif.meeting.dto;

import fr.pivot.collaboratif.meeting.AgendaItem;

import java.util.UUID;

/**
 * API response shape for a single agenda item (US12.1.1 AC1/AC2).
 *
 * @param id              item id
 * @param title           item title
 * @param durationMinutes planned duration in minutes
 * @param type            the item's category ({@code INFO}/{@code DISCUSSION}/{@code DECISION})
 * @param facilitator     optional facilitator display name, or {@code null}
 * @param position        {@code 0}-based display order within the meeting's agenda
 */
public record AgendaItemResponse(
        UUID id,
        String title,
        Integer durationMinutes,
        String type,
        String facilitator,
        int position) {

    /**
     * Builds the response shape from a persisted {@link AgendaItem}.
     *
     * @param item the persisted entity
     * @return the response DTO
     */
    public static AgendaItemResponse from(final AgendaItem item) {
        return new AgendaItemResponse(
                item.getId(), item.getTitle(), item.getDurationMinutes(),
                item.getType().name(), item.getFacilitator(), item.getPosition());
    }
}
