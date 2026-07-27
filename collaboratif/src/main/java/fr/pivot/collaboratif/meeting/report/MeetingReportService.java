package fr.pivot.collaboratif.meeting.report;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MeetingReportUnsupportedFormatException;
import fr.pivot.collaboratif.meeting.AgendaItem;
import fr.pivot.collaboratif.meeting.AgendaItemStatus;
import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingAccessService;
import fr.pivot.collaboratif.meeting.MeetingAction;
import fr.pivot.collaboratif.meeting.MeetingActionRepository;
import fr.pivot.collaboratif.meeting.MeetingDecision;
import fr.pivot.collaboratif.meeting.MeetingDecisionRepository;
import fr.pivot.collaboratif.meeting.MeetingStatus;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.ActionReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.AgendaItemReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.DecisionReportDto;
import fr.pivot.collaboratif.meeting.report.MeetingReportDto.ParticipantReportDto;
import fr.pivot.collaboratif.meeting.ws.MeetingDestinations;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for the MeetOps compte-rendu (meeting report, US12.3.1) — builds the live draft
 * or resolves the frozen snapshot ({@link #buildReport}), renders Markdown ({@link
 * #exportMarkdown}), and freezes the snapshot at closure ({@link #freezeOnClose}).
 *
 * <p>{@link #buildReport} always resolves the meeting through {@link
 * MeetingAccessService#resolveMeetingForCaller} first — tenant isolation and visibility, 404 for
 * both a genuinely unknown/cross-tenant meeting and one the caller cannot see (AC Security).
 *
 * <p><strong>No separate {@code POST .../close} route.</strong> The Gate 1 architecture note
 * names {@code POST .../meetings/{meetingId}/close} as the closure trigger; US12.2.1 (the parent
 * branch) already exposes exactly that transition as {@code POST .../end} — the sole action that
 * moves a meeting from {@code IN_PROGRESS} to the terminal {@code ENDED} status, already gated
 * owner-or-{@code ROLE_ADMIN} by {@code MeetingAccessService#resolveMeetingForOwnerOrAdmin}. Empty
 * as it would be, an additional {@code /close} alias would either duplicate that authorization
 * logic or bypass it — so {@link #freezeOnClose} is instead invoked directly from {@code
 * MeetingAnimationService}'s two existing paths that transition a meeting to {@code ENDED}
 * (explicit {@code end}, and {@code next} advancing past the last item), immediately after each
 * one persists that transition. {@link #freezeOnClose} itself performs no additional
 * authorization check — it is only ever reachable from those two already owner-or-admin-gated
 * call sites, satisfying the AC Security requirement ("réservée à owner-or-ROLE_ADMIN") without
 * re-deriving it.
 */
@Service
public class MeetingReportService {

    private final MeetingAccessService accessService;
    private final MeetingActionRepository actionRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingReportRepository reportRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final MeetingMarkdownRenderer markdownRenderer;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates the service with its required dependencies.
     *
     * @param accessService        resolves meetings with tenant/visibility enforcement
     * @param actionRepository     repository for captured in-meeting actions
     * @param decisionRepository   repository for recorded decisions
     * @param reportRepository     repository for frozen report snapshots
     * @param teamMemberRepository repository used to resolve a meeting's team-member participants
     * @param markdownRenderer     pure DTO-to-Markdown transformer
     * @param messagingTemplate    STOMP broadcaster
     * @param objectMapper         auto-configured JSON (de)serializer for the {@code content} column
     * @param clock                the shared {@code meetOpsClock}, overridable in tests
     */
    public MeetingReportService(
            final MeetingAccessService accessService,
            final MeetingActionRepository actionRepository,
            final MeetingDecisionRepository decisionRepository,
            final MeetingReportRepository reportRepository,
            final TeamMemberRepository teamMemberRepository,
            final MeetingMarkdownRenderer markdownRenderer,
            final SimpMessagingTemplate messagingTemplate,
            final ObjectMapper objectMapper,
            @Qualifier("meetOpsClock") final Clock clock) {
        this.accessService = accessService;
        this.actionRepository = actionRepository;
        this.decisionRepository = decisionRepository;
        this.reportRepository = reportRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.markdownRenderer = markdownRenderer;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Builds the compte-rendu for a caller with visibility into the meeting (AC nominal) — the
     * frozen snapshot once the meeting is {@link MeetingStatus#ENDED} and one was generated at
     * closure, otherwise a live draft derived from the current source tables.
     *
     * @param meetingId the meeting's UUID
     * @param principal the caller
     * @return the report, {@code draft=false} only for an already-frozen snapshot
     */
    @Transactional(readOnly = true)
    public MeetingReportDto buildReport(final UUID meetingId, final CollaboratifRequestPrincipal principal) {
        Meeting meeting = accessService.resolveMeetingForCaller(meetingId, principal);
        if (meeting.getStatus() == MeetingStatus.ENDED) {
            Optional<MeetingReportSnapshot> snapshot = reportRepository.findByMeetingId(meetingId);
            if (snapshot.isPresent()) {
                return deserialize(snapshot.get().getContent());
            }
        }
        return assemble(meeting, true, clock.instant());
    }

    /**
     * Renders a report as Markdown (AC nominal, {@code format=markdown}) — a thin delegation to
     * the stateless {@link MeetingMarkdownRenderer}.
     *
     * @param report the report to render
     * @return the Markdown document
     */
    public String exportMarkdown(final MeetingReportDto report) {
        return markdownRenderer.render(report);
    }

    /**
     * Validates an {@code export?format=...} query value, rejecting anything but {@code json}/
     * {@code markdown} (AC error case).
     *
     * @param format the raw {@code format} query parameter, or {@code null} (defaults to
     *               {@code json})
     * @return the normalized, lower-cased format — {@code "json"} or {@code "markdown"}
     * @throws MeetingReportUnsupportedFormatException for any other value
     */
    public String normalizeExportFormat(final String format) {
        String normalized = format == null || format.isBlank() ? "json" : format.trim().toLowerCase(Locale.ROOT);
        if (!"json".equals(normalized) && !"markdown".equals(normalized)) {
            throw new MeetingReportUnsupportedFormatException(format);
        }
        return normalized;
    }

    /**
     * Freezes the compte-rendu snapshot at meeting closure (AC nominal) — called exactly once per
     * meeting, immediately after {@code meeting.end(...)} has been persisted by the caller (see
     * this class's own Javadoc for why no separate {@code /close} route exists). Serializes the
     * assembled {@link MeetingReportDto} into {@code collaboratif.meeting_report.content},
     * inserts the row, and broadcasts {@code MEETING_REPORT_READY} on this meeting's room.
     *
     * @param meeting   the just-closed meeting ({@link MeetingStatus#ENDED}, agenda items loaded)
     * @param principal the closing caller (already verified owner-or-{@code ROLE_ADMIN} by the
     *                  caller of this method) — recorded as {@code generated_by}
     */
    @Transactional
    public void freezeOnClose(final Meeting meeting, final CollaboratifRequestPrincipal principal) {
        Instant now = clock.instant();
        MeetingReportDto dto = assemble(meeting, false, now);
        String json = serialize(dto);
        MeetingReportSnapshot snapshot = new MeetingReportSnapshot(
                meeting.getId(), meeting.getTenantId(), json, dto.actualDurationSeconds(), principal.userId(), now);
        reportRepository.save(snapshot);
        messagingTemplate.convertAndSend(
                MeetingDestinations.topicFor(meeting.getId()), new MeetingReportReadyEvent(meeting.getId(), now, false));
    }

    private MeetingReportDto assemble(final Meeting meeting, final boolean draft, final Instant now) {
        List<ParticipantReportDto> participants = buildParticipants(meeting);
        List<AgendaItemReportDto> agendaItems = meeting.getAgendaItems().stream()
                .map(item -> toAgendaItemReportDto(item, now))
                .toList();
        List<DecisionReportDto> decisions = decisionRepository.findByMeetingIdOrderByDecidedAtAsc(meeting.getId())
                .stream()
                .map(this::toDecisionReportDto)
                .toList();
        List<ActionReportDto> actions = actionRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getId())
                .stream()
                .map(this::toActionReportDto)
                .toList();
        Integer actualDurationSeconds = computeActualDurationSeconds(meeting, now);
        return new MeetingReportDto(
                meeting.getId(), meeting.getTitle(), meeting.getStatus().name(), draft, participants, agendaItems,
                decisions, actions, actualDurationSeconds, now);
    }

    private List<ParticipantReportDto> buildParticipants(final Meeting meeting) {
        Map<Long, Boolean> byUserId = new LinkedHashMap<>();
        byUserId.put(meeting.getCreatedBy(), true);
        if (meeting.getTeamId() != null) {
            for (TeamMember member : teamMemberRepository.findAllByTeamId(meeting.getTeamId())) {
                byUserId.putIfAbsent(member.getUserId(), false);
            }
        }
        return byUserId.entrySet().stream()
                .map(entry -> new ParticipantReportDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private AgendaItemReportDto toAgendaItemReportDto(final AgendaItem item, final Instant now) {
        Integer actualSeconds;
        boolean overtime;
        if (item.getItemStatus() == AgendaItemStatus.DONE) {
            actualSeconds = item.getActualSeconds();
            overtime = item.isOvertime();
        } else if (item.getItemStatus() == AgendaItemStatus.CURRENT && item.getCurrentItemStartedAt() != null) {
            long elapsed = Duration.between(item.getCurrentItemStartedAt(), now).getSeconds();
            actualSeconds = (int) elapsed;
            overtime = elapsed > (long) item.getDurationMinutes() * 60;
        } else {
            actualSeconds = null;
            overtime = false;
        }
        return new AgendaItemReportDto(item.getId(), item.getTitle(), item.getDurationMinutes(), actualSeconds, overtime);
    }

    private DecisionReportDto toDecisionReportDto(final MeetingDecision decision) {
        return new DecisionReportDto(decision.getId(), decision.getLabel(), decision.getDecidedAt());
    }

    private ActionReportDto toActionReportDto(final MeetingAction action) {
        return new ActionReportDto(action.getId(), action.getLabel(), action.getOwnerUserId(), action.getDueDate());
    }

    private Integer computeActualDurationSeconds(final Meeting meeting, final Instant now) {
        if (meeting.getStartedAt() == null) {
            return null;
        }
        Instant end = meeting.getEndedAt() != null ? meeting.getEndedAt() : now;
        return (int) Duration.between(meeting.getStartedAt(), end).getSeconds();
    }

    private String serialize(final MeetingReportDto dto) {
        return objectMapper.writeValueAsString(dto);
    }

    private MeetingReportDto deserialize(final String content) {
        return objectMapper.readValue(content, MeetingReportDto.class);
    }
}
