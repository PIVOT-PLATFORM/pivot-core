package fr.pivot.collaboratif.meeting.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Full compte-rendu (meeting report) shape for a single meeting (US12.3.1) — returned by
 * {@code GET .../report}, serialized natively for {@code GET .../report/export?format=json}
 * (AC nominal), and the input {@link MeetingMarkdownRenderer} transforms into Markdown. Also the
 * exact shape persisted (serialized as-is) into {@code collaboratif.meeting_report.content} at
 * closure — see {@code MeetingReportService#freezeOnClose}.
 *
 * <p>{@code draft} is {@code true} whenever this report was derived live from the source tables
 * (the meeting is not yet {@code ENDED}, or — defensively — {@code ENDED} but no frozen snapshot
 * exists yet) and {@code false} exactly when it was deserialized from an already-frozen {@code
 * meeting_report} row. Never both stored and computed inconsistently: once frozen, every field
 * below is exactly what was true at closure time, forever (AC Security — immutability).
 *
 * @param meetingId              the meeting's UUID
 * @param title                  meeting title
 * @param status                 lifecycle status ({@code DRAFT}/{@code CONFIRMED}/
 *                               {@code IN_PROGRESS}/{@code ENDED})
 * @param draft                  {@code true} when live-derived, {@code false} when a frozen
 *                               snapshot
 * @param participants           participants present — the organizer plus every member of the
 *                               meeting's optional team (see this class's own package-level
 *                               note on the "présence" interpretation gap)
 * @param agendaItems            every agenda point, in display order, with planned vs actual
 *                               duration and overtime
 * @param decisions              decisions recorded during the meeting, oldest first
 * @param actions                actions captured during the meeting, oldest first
 * @param actualDurationSeconds  whole-meeting actual duration in seconds — {@code null} until
 *                               the meeting has started
 * @param generatedAt            instant this report representation was produced (live "now" for
 *                               a draft, the freeze instant for a snapshot)
 */
public record MeetingReportDto(
        UUID meetingId,
        String title,
        String status,
        boolean draft,
        List<ParticipantReportDto> participants,
        List<AgendaItemReportDto> agendaItems,
        List<DecisionReportDto> decisions,
        List<ActionReportDto> actions,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer actualDurationSeconds,
        Instant generatedAt) {

    /**
     * A participant present at the meeting (US12.3.1 AC nominal).
     *
     * <p><strong>Interpretation of a genuine spec gap:</strong> this module has no dedicated
     * attendance/presence-tracking table — the only membership-adjacent record is {@code
     * MeetingMembershipCacheService}'s ephemeral 5s-TTL Redis cache used purely for STOMP
     * SUBSCRIBE authorization, unqueryable retroactively and long expired by report time. Absent
     * a real attendance log, "participants présents" is derived from the same visibility set
     * {@code MeetingAccessService#resolveMeetingForCaller} already treats as this meeting's
     * membership: the organizer, plus every member of its optional team. Revisit once a real
     * join/leave attendance log exists for MeetOps meetings.
     *
     * @param userId    the participant's {@code public.users.id}
     * @param organizer {@code true} for the meeting's creator/owner
     */
    public record ParticipantReportDto(Long userId, boolean organizer) {
    }

    /**
     * One agenda point's report line (US12.3.1 AC nominal).
     *
     * @param id                      the agenda item's UUID
     * @param title                   item title
     * @param plannedDurationMinutes  planned duration, in minutes
     * @param actualDurationSeconds   actual time spent, in seconds — {@code null} while the item
     *                                is still {@code PENDING}
     * @param overtime                {@code true} if the item ran (or is currently running) past
     *                                its planned duration
     */
    public record AgendaItemReportDto(
            UUID id,
            String title,
            Integer plannedDurationMinutes,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer actualDurationSeconds,
            boolean overtime) {
    }

    /**
     * One recorded decision (US12.3.1 AC nominal) — see {@code MeetingDecision}'s own Javadoc for
     * why this table may legitimately be empty for every meeting today.
     *
     * @param id        the decision's UUID
     * @param label     the decision's description
     * @param decidedAt timestamp the decision was recorded
     */
    public record DecisionReportDto(UUID id, String label, Instant decidedAt) {
    }

    /**
     * One captured action (US12.3.1 AC nominal).
     *
     * @param id          the action's UUID
     * @param label       the action's description
     * @param ownerUserId optional owning user's id, or {@code null} if unassigned
     * @param dueDate     optional due date, or {@code null}
     */
    public record ActionReportDto(
            UUID id,
            String label,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long ownerUserId,
            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate dueDate) {
    }
}
