package fr.pivot.collaboratif.meeting;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MeetingConflictException;
import fr.pivot.collaboratif.exception.MeetingEmptyAgendaException;
import fr.pivot.collaboratif.meeting.dto.AddMeetingActionRequest;
import fr.pivot.collaboratif.meeting.dto.AgendaItemChangedEvent;
import fr.pivot.collaboratif.meeting.dto.MeetingActionAddedEvent;
import fr.pivot.collaboratif.meeting.dto.MeetingActionDto;
import fr.pivot.collaboratif.meeting.dto.MeetingEndedEvent;
import fr.pivot.collaboratif.meeting.dto.MeetingLiveStateDto;
import fr.pivot.collaboratif.meeting.dto.MeetingStartedEvent;
import fr.pivot.collaboratif.meeting.dto.TimerTickEvent;
import fr.pivot.collaboratif.meeting.report.MeetingReportService;
import fr.pivot.collaboratif.meeting.ws.MeetingDestinations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeetingAnimationService} (US12.2.1) — every AC's happy path and error
 * branch, with a fixed {@link Clock} so every {@code elapsedSeconds}/{@code remainingSeconds}/
 * {@code overtimeSeconds} assertion is exact (AC-S4: the timer is always server-computed, never
 * approximate in a test either).
 */
@ExtendWith(MockitoExtension.class)
class MeetingAnimationServiceTest {

    private static final Long TENANT_ID = 100L;
    private static final Long OWNER_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private MeetingActionRepository actionRepository;
    @Mock
    private MeetingAccessService accessService;
    @Mock
    private MeetingReportService reportService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private MeetingAnimationService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new MeetingAnimationService(
                meetingRepository, actionRepository, accessService, reportService, messagingTemplate, fixedClock);
    }

    private CollaboratifRequestPrincipal principal() {
        return new CollaboratifRequestPrincipal(OWNER_ID, TENANT_ID, "ROLE_USER");
    }

    /** Builds a meeting with {@code itemCount} agenda items, each assigned a real, stable id. */
    private Meeting meetingWithAgenda(final MeetingStatus status, final int itemCount) {
        Meeting meeting = new Meeting(TENANT_ID, null, "Standup", Instant.now(), 30, OWNER_ID, Instant.now());
        ReflectionTestUtils.setField(meeting, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(meeting, "status", status);
        for (int i = 0; i < itemCount; i++) {
            AgendaItem item = meeting.addAgendaItem("Point " + i, 5, AgendaItemType.INFO, null);
            ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        }
        return meeting;
    }

    // -------------------------------------------------------------------------
    // AC-01 — start
    // -------------------------------------------------------------------------

    @Test
    void start_ac01_transitionsToInProgressAndMakesFirstItemCurrentAndBroadcasts() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.DRAFT, 2);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        service.start(meeting.getId(), principal());

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
        assertThat(meeting.getStartedAt()).isEqualTo(NOW);
        AgendaItem first = meeting.getAgendaItems().get(0);
        assertThat(first.getItemStatus()).isEqualTo(AgendaItemStatus.CURRENT);
        assertThat(first.getCurrentItemStartedAt()).isEqualTo(NOW);
        assertThat(meeting.getCurrentAgendaItemId()).isEqualTo(first.getId());
        verify(meetingRepository).save(meeting);

        ArgumentCaptor<MeetingStartedEvent> captor = ArgumentCaptor.forClass(MeetingStartedEvent.class);
        verify(messagingTemplate).convertAndSend(eq(MeetingDestinations.topicFor(meeting.getId())), captor.capture());
        assertThat(captor.getValue().state().status()).isEqualTo("IN_PROGRESS");
        assertThat(captor.getValue().state().currentIndex()).isZero();
    }

    @Test
    void start_ac01_fromConfirmedStatus_isAlsoAllowed() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.CONFIRMED, 1);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        service.start(meeting.getId(), principal());

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
    }

    @Test
    void start_ac_e1_whenAlreadyInProgress_throwsConflictAndNeverBroadcasts() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 1);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        assertThatThrownBy(() -> service.start(meeting.getId(), principal()))
                .isInstanceOf(MeetingConflictException.class)
                .extracting(ex -> ((MeetingConflictException) ex).getCode())
                .isEqualTo("MEETING_ALREADY_STARTED");
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void start_ac_e1_whenAlreadyEnded_throwsConflictWithDistinctCode() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.ENDED, 1);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        assertThatThrownBy(() -> service.start(meeting.getId(), principal()))
                .isInstanceOf(MeetingConflictException.class)
                .extracting(ex -> ((MeetingConflictException) ex).getCode())
                .isEqualTo("MEETING_ALREADY_ENDED");
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void start_ac_e3_whenAgendaIsEmpty_throwsEmptyAgendaAndNeverBroadcasts() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.DRAFT, 0);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        assertThatThrownBy(() -> service.start(meeting.getId(), principal()))
                .isInstanceOf(MeetingEmptyAgendaException.class);
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(meetingRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC-03 — agenda/next (manual)
    // -------------------------------------------------------------------------

    @Test
    void next_ac03_whenNotLastItem_marksCurrentDoneAndNextCurrentAndBroadcastsAgendaChanged() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 2);
        AgendaItem first = meeting.getAgendaItems().get(0);
        AgendaItem second = meeting.getAgendaItems().get(1);
        first.markCurrent(NOW.minusSeconds(60));
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", first.getId());
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        service.next(meeting.getId(), principal());

        assertThat(first.getItemStatus()).isEqualTo(AgendaItemStatus.DONE);
        assertThat(first.getActualSeconds()).isEqualTo(60);
        assertThat(second.getItemStatus()).isEqualTo(AgendaItemStatus.CURRENT);
        assertThat(meeting.getCurrentAgendaItemId()).isEqualTo(second.getId());
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);

        ArgumentCaptor<AgendaItemChangedEvent> captor = ArgumentCaptor.forClass(AgendaItemChangedEvent.class);
        verify(messagingTemplate).convertAndSend(eq(MeetingDestinations.topicFor(meeting.getId())), captor.capture());
        assertThat(captor.getValue().index()).isEqualTo(1);
        assertThat(captor.getValue().total()).isEqualTo(2);
        assertThat(captor.getValue().currentAgendaItemId()).isEqualTo(second.getId());
    }

    /** Gate 1 decision (pivot-docs PR #317): advancing past the last item closes the meeting. */
    @Test
    void next_ac03_gate1_whenCurrentIsTheLastItem_endsTheMeetingInstead() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 1);
        AgendaItem only = meeting.getAgendaItems().get(0);
        only.markCurrent(NOW.minusSeconds(10));
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", only.getId());
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        service.next(meeting.getId(), principal());

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.ENDED);
        assertThat(only.getItemStatus()).isEqualTo(AgendaItemStatus.DONE);
        assertThat(meeting.getCurrentAgendaItemId()).isNull();

        ArgumentCaptor<MeetingEndedEvent> captor = ArgumentCaptor.forClass(MeetingEndedEvent.class);
        verify(messagingTemplate).convertAndSend(eq(MeetingDestinations.topicFor(meeting.getId())), captor.capture());
        assertThat(captor.getValue().meetingId()).isEqualTo(meeting.getId());

        // US12.3.1: advancing past the last item is one of the two closure paths that must
        // freeze the compte-rendu snapshot.
        verify(reportService).freezeOnClose(meeting, principal());
    }

    @Test
    void next_ac_e2_whenMeetingNotInProgress_throwsConflict() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.DRAFT, 1);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        assertThatThrownBy(() -> service.next(meeting.getId(), principal()))
                .isInstanceOf(MeetingConflictException.class)
                .extracting(ex -> ((MeetingConflictException) ex).getCode())
                .isEqualTo("MEETING_NOT_IN_PROGRESS");
        verify(reportService, never()).freezeOnClose(any(), any());
    }

    @Test
    void next_ac03_whenNotLastItem_neverFreezesReport() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 2);
        AgendaItem first = meeting.getAgendaItems().get(0);
        first.markCurrent(NOW.minusSeconds(60));
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", first.getId());
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        service.next(meeting.getId(), principal());

        verify(reportService, never()).freezeOnClose(any(), any());
    }

    // -------------------------------------------------------------------------
    // AC-06 — end
    // -------------------------------------------------------------------------

    @Test
    void end_ac06_marksCurrentDoneAndMeetingEndedAndBroadcasts() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 2);
        AgendaItem first = meeting.getAgendaItems().get(0);
        first.markCurrent(NOW.minusSeconds(30));
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", first.getId());
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        service.end(meeting.getId(), principal());

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.ENDED);
        assertThat(meeting.getEndedAt()).isEqualTo(NOW);
        assertThat(first.getItemStatus()).isEqualTo(AgendaItemStatus.DONE);
        assertThat(meeting.getCurrentAgendaItemId()).isNull();
        verify(messagingTemplate).convertAndSend(
                eq(MeetingDestinations.topicFor(meeting.getId())), any(MeetingEndedEvent.class));

        // US12.3.1: explicit end() is the other of the two closure paths that must freeze the
        // compte-rendu snapshot — invoked only after the ENDED transition is already persisted.
        verify(reportService).freezeOnClose(meeting, principal());
    }

    @Test
    void end_ac_e2_whenMeetingNotInProgress_throwsConflict() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.DRAFT, 1);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);

        assertThatThrownBy(() -> service.end(meeting.getId(), principal()))
                .isInstanceOf(MeetingConflictException.class)
                .extracting(ex -> ((MeetingConflictException) ex).getCode())
                .isEqualTo("MEETING_NOT_IN_PROGRESS");
        verify(reportService, never()).freezeOnClose(any(), any());
    }

    // -------------------------------------------------------------------------
    // AC-08 — actions
    // -------------------------------------------------------------------------

    @Test
    void addAction_ac08_persistsAndBroadcasts() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 1);
        AgendaItem current = meeting.getAgendaItems().get(0);
        current.markCurrent(NOW);
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", current.getId());
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);
        when(actionRepository.save(any(MeetingAction.class))).thenAnswer(inv -> {
            MeetingAction saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            return saved;
        });
        AddMeetingActionRequest request = new AddMeetingActionRequest("Follow up with legal", 42L, LocalDate.now());

        MeetingActionDto dto = service.addAction(meeting.getId(), request, principal());

        assertThat(dto.label()).isEqualTo("Follow up with legal");
        assertThat(dto.ownerUserId()).isEqualTo(42L);
        assertThat(dto.agendaItemId()).isEqualTo(current.getId());

        ArgumentCaptor<MeetingAction> captor = ArgumentCaptor.forClass(MeetingAction.class);
        verify(actionRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().getMeetingId()).isEqualTo(meeting.getId());

        verify(messagingTemplate).convertAndSend(
                eq(MeetingDestinations.topicFor(meeting.getId())), any(MeetingActionAddedEvent.class));
    }

    @Test
    void addAction_ac_e2_whenMeetingNotInProgress_throwsConflictAndNeverPersists() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.DRAFT, 1);
        when(accessService.resolveMeetingForOwnerOrAdmin(meeting.getId(), principal())).thenReturn(meeting);
        AddMeetingActionRequest request = new AddMeetingActionRequest("Label", null, null);

        assertThatThrownBy(() -> service.addAction(meeting.getId(), request, principal()))
                .isInstanceOf(MeetingConflictException.class);
        verify(actionRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC-07 — live state
    // -------------------------------------------------------------------------

    @Test
    void getLive_ac07_computesServerSideTimerFromCurrentItemStartedAt() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 2);
        AgendaItem first = meeting.getAgendaItems().get(0);
        first.markCurrent(NOW.minusSeconds(100));
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", first.getId());
        when(accessService.resolveMeetingForCaller(meeting.getId(), principal())).thenReturn(meeting);

        MeetingLiveStateDto state = service.getLive(meeting.getId(), principal());

        assertThat(state.status()).isEqualTo("IN_PROGRESS");
        assertThat(state.currentIndex()).isZero();
        assertThat(state.totalItems()).isEqualTo(2);
        assertThat(state.elapsedSeconds()).isEqualTo(100);
        assertThat(state.remainingSeconds()).isEqualTo(200); // 5 min = 300s allotted - 100s elapsed
        assertThat(state.overtime()).isFalse();
        assertThat(state.agendaItems()).hasSize(2);
    }

    @Test
    void getLive_ac07_beforeStart_hasNoCurrentItemAndZeroedTimer() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.DRAFT, 1);
        when(accessService.resolveMeetingForCaller(meeting.getId(), principal())).thenReturn(meeting);

        MeetingLiveStateDto state = service.getLive(meeting.getId(), principal());

        assertThat(state.currentIndex()).isNull();
        assertThat(state.currentAgendaItemId()).isNull();
        assertThat(state.elapsedSeconds()).isZero();
        assertThat(state.overtime()).isFalse();
    }

    // -------------------------------------------------------------------------
    // AC-02/AC-04/AC-05/AC-S4 — tick (scheduler entry point)
    // -------------------------------------------------------------------------

    @Test
    void tick_ac02_broadcastsTimerTickWithServerComputedValues() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 1);
        AgendaItem current = meeting.getAgendaItems().get(0);
        current.markCurrent(NOW.minusSeconds(60));
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", current.getId());
        when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

        service.tick(meeting.getId());

        ArgumentCaptor<TimerTickEvent> captor = ArgumentCaptor.forClass(TimerTickEvent.class);
        verify(messagingTemplate).convertAndSend(eq(MeetingDestinations.topicFor(meeting.getId())), captor.capture());
        assertThat(captor.getValue().elapsedSeconds()).isEqualTo(60);
        assertThat(captor.getValue().remainingSeconds()).isEqualTo(240);
        assertThat(captor.getValue().agendaItemId()).isEqualTo(current.getId());
    }

    @Test
    void tick_ac04_pastAllottedTime_reportsOvertimeButDoesNotForceAdvanceWithoutAutoAdvance() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 2);
        AgendaItem current = meeting.getAgendaItems().get(0);
        current.markCurrent(NOW.minusSeconds(400)); // 400s > 300s allotted
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", current.getId());
        when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

        service.tick(meeting.getId());

        ArgumentCaptor<TimerTickEvent> captor = ArgumentCaptor.forClass(TimerTickEvent.class);
        verify(messagingTemplate).convertAndSend(eq(MeetingDestinations.topicFor(meeting.getId())), captor.capture());
        assertThat(captor.getValue().overtimeSeconds()).isEqualTo(100);
        assertThat(current.getItemStatus()).isEqualTo(AgendaItemStatus.CURRENT); // not force-advanced
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void tick_ac05_pastAllottedTimeWithAutoAdvanceAndNotLastItem_advancesAutomatically() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 2);
        ReflectionTestUtils.setField(meeting, "autoAdvance", true);
        AgendaItem current = meeting.getAgendaItems().get(0);
        AgendaItem next = meeting.getAgendaItems().get(1);
        current.markCurrent(NOW.minusSeconds(400));
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", current.getId());
        when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

        service.tick(meeting.getId());

        assertThat(current.getItemStatus()).isEqualTo(AgendaItemStatus.DONE);
        assertThat(next.getItemStatus()).isEqualTo(AgendaItemStatus.CURRENT);
        assertThat(meeting.getCurrentAgendaItemId()).isEqualTo(next.getId());
        verify(meetingRepository).save(meeting);
        // AC-05: "jamais de tick overtime émis avant l'avance auto" — this item is never observed
        // in overtime by a client, so no TIMER_TICK is broadcast for this tick at all, only the
        // AGENDA_ITEM_CHANGED advancing straight past it.
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(TimerTickEvent.class));
        ArgumentCaptor<AgendaItemChangedEvent> changed = ArgumentCaptor.forClass(AgendaItemChangedEvent.class);
        verify(messagingTemplate).convertAndSend(
                eq(MeetingDestinations.topicFor(meeting.getId())), changed.capture());
        assertThat(changed.getValue().trigger()).isEqualTo(AgendaItemChangedEvent.Trigger.TIMER_EXPIRED);
        assertThat(changed.getValue().previousAgendaItemId()).isEqualTo(current.getId());
    }

    /** AC-05: "sur le dernier point expiré aucune clôture automatique n'a lieu". */
    @Test
    void tick_ac05_pastAllottedTimeWithAutoAdvanceOnTheLastItem_neverAutoCloses() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 1);
        ReflectionTestUtils.setField(meeting, "autoAdvance", true);
        AgendaItem only = meeting.getAgendaItems().get(0);
        only.markCurrent(NOW.minusSeconds(400));
        ReflectionTestUtils.setField(meeting, "currentAgendaItemId", only.getId());
        when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

        service.tick(meeting.getId());

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.IN_PROGRESS); // still overtime, not ended
        assertThat(only.getItemStatus()).isEqualTo(AgendaItemStatus.CURRENT);
        verify(meetingRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(MeetingEndedEvent.class));
        verify(messagingTemplate).convertAndSend(
                eq(MeetingDestinations.topicFor(meeting.getId())), any(TimerTickEvent.class));
    }

    @Test
    void tick_whenMeetingIsNoLongerInProgress_isANoOp() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.ENDED, 1);
        when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

        service.tick(meeting.getId());

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void tick_whenMeetingHasNoCurrentItem_isANoOp() {
        Meeting meeting = meetingWithAgenda(MeetingStatus.IN_PROGRESS, 1);
        when(meetingRepository.findById(meeting.getId())).thenReturn(Optional.of(meeting));

        service.tick(meeting.getId());

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void tick_whenMeetingNoLongerExists_isANoOp() {
        UUID unknownId = UUID.randomUUID();
        when(meetingRepository.findById(unknownId)).thenReturn(Optional.empty());

        service.tick(unknownId);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }
}
