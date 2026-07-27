package fr.pivot.collaboratif.meeting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import fr.pivot.collaboratif.meeting.AgendaItem;
import fr.pivot.collaboratif.meeting.Meeting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full animation state of a meeting (US12.2.1 AC-07) — returned by {@code GET .../live} for a
 * reconnecting/joining participant, and embedded in the {@code MEETING_STARTED} broadcast. Every
 * timer field ({@code elapsedSeconds}/{@code remainingSeconds}/{@code overtime}/{@code
 * overtimeSeconds}) is always computed server-side from {@code current_item_started_at} (AC-S4,
 * see {@code MeetingTimerMath}) — never a value a client can influence.
 *
 * <p>{@code currentIndex}/{@code currentAgendaItemId} are {@code @JsonInclude(NON_NULL)}: both are
 * genuinely absent (not merely {@code null}) once the meeting has no current item — before it
 * starts, or after it ends.
 *
 * @param meetingId       meeting id
 * @param status          lifecycle status ({@code DRAFT}/{@code CONFIRMED}/{@code IN_PROGRESS}/
 *                        {@code ENDED})
 * @param currentIndex    {@code 0}-based index of the current item within {@code agendaItems}, or
 *                        {@code null} (omitted) when there is none
 * @param totalItems      total number of agenda items
 * @param currentAgendaItemId id of the current agenda item, or {@code null} (omitted)
 * @param elapsedSeconds  seconds since the current item became current; {@code 0} when there is
 *                        no current item
 * @param remainingSeconds allotted-minus-elapsed for the current item; negative in overtime;
 *                         {@code 0} when there is no current item
 * @param overtime        {@code true} once {@code remainingSeconds} goes negative (AC-04)
 * @param overtimeSeconds {@code max(0, -remainingSeconds)}
 * @param agendaItems     every agenda item, in display order, with its own animation status
 * @param serverTime      the server instant this state was computed at — lets a client compute
 *                         and correct its own clock offset rather than trusting local receipt time
 */
public record MeetingLiveStateDto(
        UUID meetingId,
        String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer currentIndex,
        int totalItems,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID currentAgendaItemId,
        long elapsedSeconds,
        long remainingSeconds,
        boolean overtime,
        long overtimeSeconds,
        List<AgendaItemStateDto> agendaItems,
        Instant serverTime) {

    /**
     * Builds the live-state shape from a persisted {@link Meeting} and a server-computed timer
     * snapshot for its current item (US12.2.1 AC-07).
     *
     * @param meeting          the meeting, with its agenda items already loaded
     * @param elapsedSeconds   server-computed elapsed seconds for the current item ({@code 0} if
     *                         none)
     * @param remainingSeconds server-computed remaining seconds ({@code 0} if none)
     * @param overtime         server-computed overtime flag ({@code false} if none)
     * @param overtimeSeconds  server-computed overtime seconds ({@code 0} if none)
     * @param now              the server-authoritative instant this state was computed at (AC-S4
     *                         — always the caller's own {@code clock.instant()}, never {@link
     *                         Instant#now()} taken directly)
     * @return the live-state DTO
     */
    public static MeetingLiveStateDto from(
            final Meeting meeting, final long elapsedSeconds, final long remainingSeconds,
            final boolean overtime, final long overtimeSeconds, final Instant now) {
        List<AgendaItemStateDto> items = meeting.getAgendaItems().stream()
                .map(AgendaItemStateDto::from)
                .toList();
        UUID currentId = meeting.getCurrentAgendaItemId();
        Integer currentIndex = currentId == null ? null
                : indexOf(meeting.getAgendaItems(), currentId);
        return new MeetingLiveStateDto(
                meeting.getId(), meeting.getStatus().name(), currentIndex, items.size(), currentId,
                elapsedSeconds, remainingSeconds, overtime, overtimeSeconds, items, now);
    }

    private static Integer indexOf(final List<AgendaItem> items, final UUID id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).getId())) {
                return i;
            }
        }
        return null;
    }
}
