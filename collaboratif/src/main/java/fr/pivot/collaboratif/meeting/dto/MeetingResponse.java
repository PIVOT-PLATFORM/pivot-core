package fr.pivot.collaboratif.meeting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.pivot.collaboratif.meeting.Meeting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response shape for a meeting (US12.1.1 AC1) — returned by {@code POST .../meetings}.
 *
 * <p>{@code agendaDurationMismatch} is {@code @JsonInclude(NON_NULL)}: it is {@code null} (and
 * therefore <strong>absent</strong> from the serialized payload, not merely {@code null}) when
 * there is no reconciliation warning to surface (AC4 — empty agenda; or AC3's sums already
 * matching) — mirrors {@code fr.pivot.collaboratif.session.poll.dto.PollOptionResult}'s identical
 * use of the annotation for the same "field genuinely absent, not null" contract.
 *
 * @param id                      meeting id
 * @param title                   meeting title
 * @param status                  lifecycle status ({@link Meeting#getStatus()}{@code .name()})
 * @param scheduledAt             scheduled date/time
 * @param totalDurationMinutes    planned total duration in minutes
 * @param teamId                  optional owning team id, or {@code null} for a personal meeting
 * @param agendaItems             the agenda, in display order (possibly empty, never {@code null})
 * @param createdAt               creation timestamp
 * @param agendaDurationMismatch  non-blocking duration reconciliation warning, or {@code null}
 *                                (omitted) when not applicable
 */
public record MeetingResponse(
        UUID id,
        String title,
        String status,
        Instant scheduledAt,
        Integer totalDurationMinutes,
        Long teamId,
        List<AgendaItemResponse> agendaItems,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) AgendaDurationMismatch agendaDurationMismatch) {

    /**
     * Builds the response shape from a persisted {@link Meeting} plus the on-the-fly computed
     * duration mismatch.
     *
     * @param meeting                the persisted entity, with its agenda items already loaded
     * @param agendaDurationMismatch the computed mismatch, or {@code null} when not applicable
     * @return the response DTO
     */
    public static MeetingResponse from(final Meeting meeting, final AgendaDurationMismatch agendaDurationMismatch) {
        List<AgendaItemResponse> items = meeting.getAgendaItems().stream()
                .map(AgendaItemResponse::from)
                .toList();
        return new MeetingResponse(
                meeting.getId(), meeting.getTitle(), meeting.getStatus().name(), meeting.getScheduledAt(),
                meeting.getTotalDurationMinutes(), meeting.getTeamId(), items, meeting.getCreatedAt(),
                agendaDurationMismatch);
    }
}
