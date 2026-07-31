package fr.pivot.collaboratif.meeting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request payload for a single agenda item within {@link CreateMeetingRequest} (US12.1.1 AC2).
 *
 * <p>{@code type} is deliberately a validated {@code String} (not a Java enum field bound
 * directly by Jackson) so an out-of-enumeration value fails Bean Validation with a proper
 * {@code application/problem+json} response (AC6) instead of a raw Jackson deserialization
 * error before validation even runs — same reasoning as {@code
 * fr.pivot.collaboratif.whiteboard.board.dto.CreateBoardRequest#enabledActivities}, which
 * validates its own whitelist at the service layer for an analogous reason.
 *
 * @param title           item title, 1-200 characters
 * @param durationMinutes planned duration in minutes, strictly positive
 * @param type            one of {@code INFO}/{@code DISCUSSION}/{@code DECISION}
 * @param facilitator     optional free-text facilitator display name, max 200 characters
 */
public record AgendaItemRequest(
        @NotBlank(message = "INVALID_AGENDA_ITEM_TITLE")
        @Size(min = 1, max = 200, message = "INVALID_AGENDA_ITEM_TITLE")
        String title,

        @NotNull(message = "INVALID_AGENDA_ITEM_DURATION")
        @Positive(message = "INVALID_AGENDA_ITEM_DURATION")
        Integer durationMinutes,

        @NotNull(message = "INVALID_AGENDA_ITEM_TYPE")
        @Pattern(regexp = "INFO|DISCUSSION|DECISION", message = "INVALID_AGENDA_ITEM_TYPE")
        String type,

        @Size(max = 200, message = "INVALID_AGENDA_ITEM_FACILITATOR")
        String facilitator) {
}
