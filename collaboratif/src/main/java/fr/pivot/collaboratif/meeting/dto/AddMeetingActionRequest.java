package fr.pivot.collaboratif.meeting.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/collaboratif/meetings/{id}/actions} (US12.2.1 AC-08).
 *
 * <p>{@code label} blank/missing, or {@code dueDate} strictly before today, are both rejected by
 * Bean Validation at the controller boundary — HTTP 400 with a machine-readable {@code code}
 * (AC-E4), before {@code MeetingAnimationService} is even invoked, so nothing is ever persisted
 * on an invalid request.
 *
 * @param label       the action's description, never blank
 * @param ownerUserId optional owning user's {@code public.users.id}, or {@code null} if
 *                     unassigned
 * @param dueDate     optional due date; when present, never strictly before today
 */
public record AddMeetingActionRequest(
        @NotBlank(message = "INVALID_LABEL")
        String label,

        Long ownerUserId,

        @FutureOrPresent(message = "INVALID_DUE_DATE")
        LocalDate dueDate) {
}
