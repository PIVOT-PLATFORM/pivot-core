package fr.pivot.collaboratif.meeting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Request body for {@code POST /api/collaboratif/meetings} (US12.1.1 AC1).
 *
 * <p>{@code teamId} is only used to resolve and validate the owning team (AC5/AC7) — never a
 * source of {@code tenantId} (that always comes from {@code TenantContext}, AC8). This record
 * carries no {@code tenantId}/{@code ownerId} field at all, so a caller cannot even attempt to
 * supply one — any such value present in the raw JSON body is silently ignored by Jackson
 * binding, satisfying AC8's "tout tenantId/ownerId du payload est ignoré" by construction.
 *
 * @param title                 meeting title, 1-200 characters
 * @param scheduledAt           scheduled date/time, ISO-8601
 * @param totalDurationMinutes  planned total duration in minutes, bounds [1, 1440]
 * @param teamId                optional owning team's {@code public.teams.id}
 * @param agendaItems           optional/possibly-empty list of agenda items, in display order
 */
public record CreateMeetingRequest(
        @NotBlank(message = "INVALID_TITLE")
        @Size(min = 1, max = 200, message = "INVALID_TITLE")
        String title,

        @NotNull(message = "INVALID_SCHEDULED_AT")
        Instant scheduledAt,

        @NotNull(message = "INVALID_TOTAL_DURATION_MINUTES")
        @Min(value = 1, message = "INVALID_TOTAL_DURATION_MINUTES")
        @Max(value = 1440, message = "INVALID_TOTAL_DURATION_MINUTES")
        Integer totalDurationMinutes,

        Long teamId,

        @Valid
        List<AgendaItemRequest> agendaItems) {
}
