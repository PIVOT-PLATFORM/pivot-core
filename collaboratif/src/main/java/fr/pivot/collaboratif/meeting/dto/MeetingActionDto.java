package fr.pivot.collaboratif.meeting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.pivot.collaboratif.meeting.MeetingAction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API/STOMP response shape for a captured meeting action (US12.2.1 AC-08).
 *
 * @param id           action id
 * @param meetingId    the meeting this action was captured during
 * @param agendaItemId the agenda item current at capture time, or {@code null} (omitted)
 * @param label        the action's description
 * @param ownerUserId  optional owning user's id, or {@code null} (omitted)
 * @param dueDate      optional due date, or {@code null} (omitted)
 * @param status       workflow status ({@code OPEN} at creation)
 * @param createdAt    capture timestamp
 */
public record MeetingActionDto(
        UUID id,
        UUID meetingId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID agendaItemId,
        String label,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long ownerUserId,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate dueDate,
        String status,
        Instant createdAt) {

    /**
     * Builds the response shape from a persisted {@link MeetingAction}.
     *
     * @param action the persisted entity
     * @return the response DTO
     */
    public static MeetingActionDto from(final MeetingAction action) {
        return new MeetingActionDto(
                action.getId(), action.getMeetingId(), action.getAgendaItemId(), action.getLabel(),
                action.getOwnerUserId(), action.getDueDate(), action.getStatus(), action.getCreatedAt());
    }
}
