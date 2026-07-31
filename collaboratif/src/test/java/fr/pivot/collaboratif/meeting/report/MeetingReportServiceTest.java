package fr.pivot.collaboratif.meeting.report;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MeetingConflictException;
import fr.pivot.collaboratif.exception.MeetingForbiddenException;
import fr.pivot.collaboratif.exception.MeetingReportUnsupportedFormatException;
import fr.pivot.collaboratif.meeting.AgendaItem;
import fr.pivot.collaboratif.meeting.AgendaItemStatus;
import fr.pivot.collaboratif.meeting.AgendaItemType;
import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingAccessService;
import fr.pivot.collaboratif.meeting.MeetingAction;
import fr.pivot.collaboratif.meeting.MeetingActionRepository;
import fr.pivot.collaboratif.meeting.MeetingDecision;
import fr.pivot.collaboratif.meeting.MeetingDecisionRepository;
import fr.pivot.collaboratif.meeting.MeetingStatus;
import fr.pivot.collaboratif.meeting.ws.MeetingDestinations;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeetingReportService} (US12.3.1) — draft-vs-frozen resolution, live
 * derivation from source tables, freeze-on-close persistence/broadcast, snapshot immutability,
 * and export-format validation. Uses a real {@link ObjectMapper} (records serialize/deserialize
 * natively) rather than mocking JSON (de)serialization.
 */
@ExtendWith(MockitoExtension.class)
class MeetingReportServiceTest {

    private static final Long TENANT_ID = 100L;
    private static final Long OWNER_ID = 1L;
    private static final Long TEAM_ID = 55L;
    private static final Instant NOW = Instant.parse("2026-08-01T11:00:00Z");

    @Mock
    private MeetingAccessService accessService;
    @Mock
    private MeetingActionRepository actionRepository;
    @Mock
    private MeetingDecisionRepository decisionRepository;
    @Mock
    private MeetingReportRepository reportRepository;
    @Mock
    private TeamMemberRepository teamMemberRepository;
    @Mock
    private MeetingMarkdownRenderer markdownRenderer;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private MeetingReportService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new MeetingReportService(
                accessService, actionRepository, decisionRepository, reportRepository, teamMemberRepository,
                markdownRenderer, messagingTemplate, new ObjectMapper(), fixedClock);
    }

    private CollaboratifRequestPrincipal principal() {
        return new CollaboratifRequestPrincipal(OWNER_ID, TENANT_ID, "ROLE_USER");
    }

    private Meeting meeting(final MeetingStatus status, final Long teamId) {
        Meeting meeting = new Meeting(TENANT_ID, teamId, "Sprint Review", Instant.now(), 30, OWNER_ID, Instant.now());
        ReflectionTestUtils.setField(meeting, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(meeting, "status", status);
        return meeting;
    }

    // -------------------------------------------------------------------------
    // buildReport — draft derivation
    // -------------------------------------------------------------------------

    @Test
    void buildReport_meetingInProgress_returnsDraftDerivedLiveAndNeverQueriesSnapshot() {
        Meeting meeting = meeting(MeetingStatus.IN_PROGRESS, TEAM_ID);
        ReflectionTestUtils.setField(meeting, "startedAt", NOW.minusSeconds(600));
        AgendaItem done = meeting.addAgendaItem("Point A", 5, AgendaItemType.INFO, null);
        ReflectionTestUtils.setField(done, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(done, "itemStatus", AgendaItemStatus.DONE);
        ReflectionTestUtils.setField(done, "actualSeconds", 400);
        ReflectionTestUtils.setField(done, "overtime", true);
        AgendaItem current = meeting.addAgendaItem("Point B", 10, AgendaItemType.DISCUSSION, null);
        ReflectionTestUtils.setField(current, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(current, "itemStatus", AgendaItemStatus.CURRENT);
        ReflectionTestUtils.setField(current, "currentItemStartedAt", NOW.minusSeconds(120));
        AgendaItem pending = meeting.addAgendaItem("Point C", 5, AgendaItemType.INFO, null);
        ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());
        when(accessService.resolveMeetingForCaller(meeting.getId(), principal())).thenReturn(meeting);
        when(decisionRepository.findByMeetingIdOrderByDecidedAtAsc(meeting.getId())).thenReturn(List.of());
        when(actionRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getId())).thenReturn(List.of());

        MeetingReportDto report = service.buildReport(meeting.getId(), principal());

        assertThat(report.draft()).isTrue();
        assertThat(report.status()).isEqualTo("IN_PROGRESS");
        assertThat(report.actualDurationSeconds()).isEqualTo(600);
        assertThat(report.agendaItems()).hasSize(3);
        assertThat(report.agendaItems().get(0).actualDurationSeconds()).isEqualTo(400);
        assertThat(report.agendaItems().get(0).overtime()).isTrue();
        assertThat(report.agendaItems().get(1).actualDurationSeconds()).isEqualTo(120);
        assertThat(report.agendaItems().get(1).overtime()).isFalse(); // 120s < 600s allotted
        assertThat(report.agendaItems().get(2).actualDurationSeconds()).isNull();
        assertThat(report.agendaItems().get(2).overtime()).isFalse();
        verify(reportRepository, never()).findByMeetingId(any());
    }

    @Test
    void buildReport_draftMeetingNotYetStarted_hasNullActualDuration() {
        Meeting meeting = meeting(MeetingStatus.DRAFT, null);
        when(accessService.resolveMeetingForCaller(meeting.getId(), principal())).thenReturn(meeting);
        when(decisionRepository.findByMeetingIdOrderByDecidedAtAsc(meeting.getId())).thenReturn(List.of());
        when(actionRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getId())).thenReturn(List.of());

        MeetingReportDto report = service.buildReport(meeting.getId(), principal());

        assertThat(report.draft()).isTrue();
        assertThat(report.actualDurationSeconds()).isNull();
        assertThat(report.participants()).containsExactly(
                new MeetingReportDto.ParticipantReportDto(OWNER_ID, true));
    }

    @Test
    void buildReport_participants_areOrganizerPlusDedupedTeamMembers() {
        Meeting meeting = meeting(MeetingStatus.DRAFT, TEAM_ID);
        when(accessService.resolveMeetingForCaller(meeting.getId(), principal())).thenReturn(meeting);
        when(decisionRepository.findByMeetingIdOrderByDecidedAtAsc(meeting.getId())).thenReturn(List.of());
        when(actionRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getId())).thenReturn(List.of());
        TeamMember ownerAsMember = new TeamMember(TEAM_ID, OWNER_ID);
        TeamMember otherMember = new TeamMember(TEAM_ID, 2L);
        when(teamMemberRepository.findAllByTeamId(TEAM_ID)).thenReturn(List.of(ownerAsMember, otherMember));

        MeetingReportDto report = service.buildReport(meeting.getId(), principal());

        assertThat(report.participants()).containsExactly(
                new MeetingReportDto.ParticipantReportDto(OWNER_ID, true),
                new MeetingReportDto.ParticipantReportDto(2L, false));
    }

    @Test
    void buildReport_includesDecisionsAndActionsScopedToTheMeeting() {
        Meeting meeting = meeting(MeetingStatus.IN_PROGRESS, null);
        ReflectionTestUtils.setField(meeting, "startedAt", NOW.minusSeconds(10));
        when(accessService.resolveMeetingForCaller(meeting.getId(), principal())).thenReturn(meeting);
        MeetingDecision decision = new MeetingDecision(
                TENANT_ID, meeting.getId(), null, "Adopter le nouveau format", NOW.minusSeconds(5), OWNER_ID);
        ReflectionTestUtils.setField(decision, "id", UUID.randomUUID());
        when(decisionRepository.findByMeetingIdOrderByDecidedAtAsc(meeting.getId())).thenReturn(List.of(decision));
        MeetingAction action = new MeetingAction(
                TENANT_ID, meeting.getId(), null, "Follow up", 9L, LocalDate.of(2026, 9, 1), NOW.minusSeconds(3));
        ReflectionTestUtils.setField(action, "id", UUID.randomUUID());
        when(actionRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getId())).thenReturn(List.of(action));

        MeetingReportDto report = service.buildReport(meeting.getId(), principal());

        assertThat(report.decisions()).hasSize(1);
        assertThat(report.decisions().get(0).label()).isEqualTo("Adopter le nouveau format");
        assertThat(report.actions()).hasSize(1);
        assertThat(report.actions().get(0).label()).isEqualTo("Follow up");
        assertThat(report.actions().get(0).ownerUserId()).isEqualTo(9L);
        verify(decisionRepository).findByMeetingIdOrderByDecidedAtAsc(meeting.getId());
        verify(actionRepository).findByMeetingIdOrderByCreatedAtAsc(meeting.getId());
    }

    // -------------------------------------------------------------------------
    // buildReport — frozen snapshot resolution / immutability
    // -------------------------------------------------------------------------

    @Test
    void buildReport_endedMeetingWithSnapshot_returnsFrozenContentWithoutTouchingSourceTables() {
        Meeting meeting = meeting(MeetingStatus.ENDED, null);
        when(accessService.resolveMeetingForCaller(meeting.getId(), principal())).thenReturn(meeting);
        MeetingReportDto frozenDto = new MeetingReportDto(
                meeting.getId(), "Sprint Review", "ENDED", false, List.of(), List.of(), List.of(), List.of(), 900,
                NOW.minusSeconds(3600));
        String json = new ObjectMapper().writeValueAsString(frozenDto);
        MeetingReportSnapshot snapshot = new MeetingReportSnapshot(
                meeting.getId(), TENANT_ID, json, 900, OWNER_ID, NOW.minusSeconds(3600));
        when(reportRepository.findByMeetingId(meeting.getId())).thenReturn(Optional.of(snapshot));

        MeetingReportDto report = service.buildReport(meeting.getId(), principal());

        assertThat(report.draft()).isFalse();
        assertThat(report.actualDurationSeconds()).isEqualTo(900);
        assertThat(report.generatedAt()).isEqualTo(NOW.minusSeconds(3600));
        // Immutability: a frozen report is resolved purely from the stored snapshot — it never
        // re-aggregates agenda_items/meeting_decisions/meeting_actions, so a later edit to any
        // of those tables (US12.3.2) can never leak into an already-closed meeting's report.
        verify(decisionRepository, never()).findByMeetingIdOrderByDecidedAtAsc(any());
        verify(actionRepository, never()).findByMeetingIdOrderByCreatedAtAsc(any());
    }

    @Test
    void buildReport_endedMeetingWithoutSnapshot_defensivelyFallsBackToLiveDraft() {
        Meeting meeting = meeting(MeetingStatus.ENDED, null);
        when(accessService.resolveMeetingForCaller(meeting.getId(), principal())).thenReturn(meeting);
        when(reportRepository.findByMeetingId(meeting.getId())).thenReturn(Optional.empty());
        when(decisionRepository.findByMeetingIdOrderByDecidedAtAsc(meeting.getId())).thenReturn(List.of());
        when(actionRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getId())).thenReturn(List.of());

        MeetingReportDto report = service.buildReport(meeting.getId(), principal());

        assertThat(report.draft()).isTrue();
    }

    // -------------------------------------------------------------------------
    // freezeOnClose
    // -------------------------------------------------------------------------

    @Test
    void freezeOnClose_persistsSnapshotWithGeneratedByAndBroadcastsReportReady() {
        Meeting meeting = meeting(MeetingStatus.ENDED, null);
        ReflectionTestUtils.setField(meeting, "startedAt", NOW.minusSeconds(300));
        ReflectionTestUtils.setField(meeting, "endedAt", NOW);
        when(decisionRepository.findByMeetingIdOrderByDecidedAtAsc(meeting.getId())).thenReturn(List.of());
        when(actionRepository.findByMeetingIdOrderByCreatedAtAsc(meeting.getId())).thenReturn(List.of());

        service.freezeOnClose(meeting, principal());

        ArgumentCaptor<MeetingReportSnapshot> captor = ArgumentCaptor.forClass(MeetingReportSnapshot.class);
        verify(reportRepository).save(captor.capture());
        MeetingReportSnapshot saved = captor.getValue();
        assertThat(saved.getMeetingId()).isEqualTo(meeting.getId());
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getGeneratedBy()).isEqualTo(OWNER_ID);
        assertThat(saved.getActualDurationSeconds()).isEqualTo(300);
        assertThat(saved.getContent()).contains("\"draft\":false");

        ArgumentCaptor<MeetingReportReadyEvent> eventCaptor = ArgumentCaptor.forClass(MeetingReportReadyEvent.class);
        verify(messagingTemplate).convertAndSend(
                eq(MeetingDestinations.topicFor(meeting.getId())), eventCaptor.capture());
        assertThat(eventCaptor.getValue().meetingId()).isEqualTo(meeting.getId());
        assertThat(eventCaptor.getValue().draft()).isFalse();
    }

    // -------------------------------------------------------------------------
    // share — AC7/AC8/AC-E/AC-Security
    // -------------------------------------------------------------------------

    @Test
    void share_endedMeeting_ownerOrAdmin_broadcastsMeetingReportSharedEvent() {
        Meeting meeting = meeting(MeetingStatus.ENDED, TEAM_ID);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        service.share(meeting.getId(), principal());

        ArgumentCaptor<MeetingReportSharedEvent> eventCaptor = ArgumentCaptor.forClass(MeetingReportSharedEvent.class);
        verify(messagingTemplate).convertAndSend(
                eq(MeetingDestinations.topicFor(meeting.getId())), eventCaptor.capture());
        assertThat(eventCaptor.getValue().meetingId()).isEqualTo(meeting.getId());
        assertThat(eventCaptor.getValue().sharedBy()).isEqualTo(OWNER_ID);
        assertThat(eventCaptor.getValue().type()).isEqualTo(MeetingReportSharedEvent.EVENT_TYPE);
    }

    @Test
    void share_meetingNotEnded_throwsConflictAndNeverBroadcasts() {
        Meeting meeting = meeting(MeetingStatus.IN_PROGRESS, TEAM_ID);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        assertThatThrownBy(() -> service.share(meeting.getId(), principal()))
                .isInstanceOf(MeetingConflictException.class)
                .extracting(ex -> ((MeetingConflictException) ex).getCode())
                .isEqualTo("MEETING_NOT_CLOSED");
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void share_nonOwnerNonAdmin_propagatesForbiddenFromAccessServiceBeforeAnyBroadcast() {
        Meeting meeting = meeting(MeetingStatus.ENDED, TEAM_ID);
        UUID meetingId = meeting.getId();
        when(accessService.resolveMeetingForOwnerOrAdmin(eq(meetingId), any()))
                .thenThrow(new MeetingForbiddenException(
                        "MEETING_FACILITATOR_ONLY", "Caller is not the meeting's owner or an admin"));

        assertThatThrownBy(() -> service.share(meetingId, principal()))
                .isInstanceOf(MeetingForbiddenException.class);
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    // -------------------------------------------------------------------------
    // export format validation
    // -------------------------------------------------------------------------

    @Test
    void normalizeExportFormat_nullDefaultsToJson() {
        assertThat(service.normalizeExportFormat(null)).isEqualTo("json");
    }

    @Test
    void normalizeExportFormat_acceptsJsonAndMarkdownCaseInsensitively() {
        assertThat(service.normalizeExportFormat("JSON")).isEqualTo("json");
        assertThat(service.normalizeExportFormat("Markdown")).isEqualTo("markdown");
    }

    @Test
    void normalizeExportFormat_rejectsUnsupportedFormat() {
        assertThatThrownBy(() -> service.normalizeExportFormat("xml"))
                .isInstanceOf(MeetingReportUnsupportedFormatException.class)
                .hasMessageContaining("xml");
    }

    @Test
    void exportMarkdown_delegatesToRenderer() {
        MeetingReportDto report = new MeetingReportDto(
                UUID.randomUUID(), "Title", "ENDED", false, List.of(), List.of(), List.of(), List.of(), null, NOW);
        when(markdownRenderer.render(report)).thenReturn("# Title\n");

        String markdown = service.exportMarkdown(report);

        assertThat(markdown).isEqualTo("# Title\n");
    }
}
