package fr.pivot.collaboratif.meetops.booking;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MalformedWindowEventException;
import fr.pivot.collaboratif.exception.MeetingConflictException;
import fr.pivot.collaboratif.exception.MeetingForbiddenException;
import fr.pivot.collaboratif.exception.MeetingNotFoundException;
import fr.pivot.collaboratif.exception.MeetingSlotInvalidException;
import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingRepository;
import fr.pivot.collaboratif.meetops.bestslot.BestSlotEngine;
import fr.pivot.collaboratif.meetops.booking.ws.MeetingRealtimePublisher;
import fr.pivot.collaboratif.meetops.bus.WindowCreatedEvent;
import fr.pivot.collaboratif.meetops.bus.WindowDeletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BookingService} (US12.4.1) — guard-clause / state-machine branches,
 * mocking out persistence and cross-cutting collaborators (mirrors {@code
 * ModuleSessionServiceTest}'s Mockito-based pattern). End-to-end/DB-backed scenarios (idempotent
 * upsert, cascade deletes, best-slot persistence) live in {@link BookingControllerIT}.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long ORGANIZER_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;
    private static final UUID MEETING_ID = UUID.randomUUID();

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private ProposedSlotRepository proposedSlotRepository;
    @Mock
    private MeetingParticipantRepository meetingParticipantRepository;
    @Mock
    private BestSlotEngine bestSlotEngine;
    @Mock
    private MeetingRealtimePublisher realtimePublisher;
    @Mock
    private MeetingInvitationSender invitationSender;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private BookingService bookingService;
    private CollaboratifRequestPrincipal organizerPrincipal;
    private CollaboratifRequestPrincipal otherUserPrincipal;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
                meetingRepository, proposedSlotRepository, meetingParticipantRepository, bestSlotEngine,
                realtimePublisher, invitationSender, eventPublisher);
        organizerPrincipal = new CollaboratifRequestPrincipal(ORGANIZER_ID, TENANT_ID, "ROLE_USER");
        otherUserPrincipal = new CollaboratifRequestPrincipal(OTHER_USER_ID, TENANT_ID, "ROLE_USER");
    }

    // -------------------------------------------------------------------------
    // Error — événement malformé
    // -------------------------------------------------------------------------

    @Test
    void consumeWindowCreated_emptyParticipants_throwsMalformed_andNeverSavesAMeeting() {
        WindowCreatedEvent event = new WindowCreatedEvent(
                TENANT_ID, "evt-1", "proj-1", "Sprint Review", List.of(),
                Instant.parse("2026-08-03T09:00:00Z"), Instant.parse("2026-08-03T11:00:00Z"), 30);

        assertThatThrownBy(() -> bookingService.consumeWindowCreated(event))
                .isInstanceOf(MalformedWindowEventException.class);
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void consumeWindowCreated_endBeforeStart_throwsMalformed() {
        WindowCreatedEvent event = new WindowCreatedEvent(
                TENANT_ID, "evt-1", "proj-1", "Sprint Review", List.of("a@pivot.test"),
                Instant.parse("2026-08-03T11:00:00Z"), Instant.parse("2026-08-03T09:00:00Z"), 30);

        assertThatThrownBy(() -> bookingService.consumeWindowCreated(event))
                .isInstanceOf(MalformedWindowEventException.class);
    }

    @Test
    void consumeWindowCreated_durationExceedsPeriod_throwsMalformed() {
        WindowCreatedEvent event = new WindowCreatedEvent(
                TENANT_ID, "evt-1", "proj-1", "Sprint Review", List.of("a@pivot.test"),
                Instant.parse("2026-08-03T09:00:00Z"), Instant.parse("2026-08-03T09:10:00Z"), 30);

        assertThatThrownBy(() -> bookingService.consumeWindowCreated(event))
                .isInstanceOf(MalformedWindowEventException.class);
    }

    @Test
    void consumeWindowCreated_missingEventRef_throwsMalformed() {
        WindowCreatedEvent event = new WindowCreatedEvent(
                TENANT_ID, null, "proj-1", "Sprint Review", List.of("a@pivot.test"),
                Instant.parse("2026-08-03T09:00:00Z"), Instant.parse("2026-08-03T11:00:00Z"), 30);

        assertThatThrownBy(() -> bookingService.consumeWindowCreated(event))
                .isInstanceOf(MalformedWindowEventException.class);
    }

    // -------------------------------------------------------------------------
    // Idempotence
    // -------------------------------------------------------------------------

    @Test
    void consumeWindowCreated_existingEventRef_isNoOp() {
        WindowCreatedEvent event = new WindowCreatedEvent(
                TENANT_ID, "evt-1", "proj-1", "Sprint Review", List.of("a@pivot.test"),
                Instant.parse("2026-08-03T09:00:00Z"), Instant.parse("2026-08-03T11:00:00Z"), 30);
        when(meetingRepository.findByTenantIdAndEventRef(TENANT_ID, "evt-1"))
                .thenReturn(Optional.of(mockMeeting()));

        bookingService.consumeWindowCreated(event);

        verify(meetingRepository, never()).save(any());
        verify(bestSlotEngine, never()).rank(any(), any(), anyInt(), any());
    }

    // -------------------------------------------------------------------------
    // Sécurité — isolation tenant / autorisation validation
    // -------------------------------------------------------------------------

    @Test
    void confirm_meetingNotInCallerTenant_throwsMeetingNotFound() {
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.confirm(MEETING_ID, UUID.randomUUID(), organizerPrincipal))
                .isInstanceOf(MeetingNotFoundException.class);
    }

    @Test
    void confirm_callerIsNotOrganizer_throwsForbidden() {
        Meeting meeting = mockMeeting();
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));

        assertThatThrownBy(() -> bookingService.confirm(MEETING_ID, UUID.randomUUID(), otherUserPrincipal))
                .isInstanceOf(MeetingForbiddenException.class);
    }

    @Test
    void confirm_organizerUnresolved_alwaysThrowsForbidden() {
        Meeting meeting = Meeting.preReserve(
                TENANT_ID, "Title", Instant.now(), Instant.now().plusSeconds(3600), 30, "evt-1", null, null,
                Instant.now());
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));

        assertThatThrownBy(() -> bookingService.confirm(MEETING_ID, UUID.randomUUID(), organizerPrincipal))
                .isInstanceOf(MeetingForbiddenException.class);
    }

    // -------------------------------------------------------------------------
    // Error — double confirmation / créneau invalide
    // -------------------------------------------------------------------------

    @Test
    void confirm_alreadyConfirmed_throwsConflict_andNeverRepublishes() {
        Meeting meeting = mockMeeting();
        meeting.confirm(Instant.now(), Instant.now());
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));

        assertThatThrownBy(() -> bookingService.confirm(MEETING_ID, UUID.randomUUID(), organizerPrincipal))
                .isInstanceOf(MeetingConflictException.class)
                .hasFieldOrPropertyWithValue("code", "ALREADY_CONFIRMED");
        verify(eventPublisher, never()).publishEvent(any());
        verify(invitationSender, never()).sendInvitations(any(), any());
    }

    @Test
    void confirm_slotNotInProposedSlots_throwsSlotInvalid() {
        Meeting meeting = mockMeeting();
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));
        when(proposedSlotRepository.findByIdAndMeetingId(any(), eq(MEETING_ID))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.confirm(MEETING_ID, UUID.randomUUID(), organizerPrincipal))
                .isInstanceOf(MeetingSlotInvalidException.class);
    }

    // -------------------------------------------------------------------------
    // window.deleted — no-op for unknown eventRef
    // -------------------------------------------------------------------------

    @Test
    void consumeWindowDeleted_unknownEventRef_isNoOp() {
        when(meetingRepository.findByTenantIdAndEventRef(TENANT_ID, "unknown")).thenReturn(Optional.empty());

        bookingService.consumeWindowDeleted(new WindowDeletedEvent(TENANT_ID, "unknown"));

        verify(meetingRepository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // -------------------------------------------------------------------------
    // adjustSlot — validation
    // -------------------------------------------------------------------------

    @Test
    void adjustSlot_endBeforeStart_throwsIllegalArgument() {
        Meeting meeting = mockMeeting();
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));
        Instant start = Instant.parse("2026-08-03T10:00:00Z");
        Instant end = Instant.parse("2026-08-03T09:00:00Z");

        assertThatThrownBy(() -> bookingService.adjustSlot(MEETING_ID, UUID.randomUUID(), start, end, organizerPrincipal))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adjustSlot_meetingAlreadyConfirmed_throwsConflict() {
        Meeting meeting = mockMeeting();
        meeting.confirm(Instant.now(), Instant.now());
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));
        Instant start = Instant.parse("2026-08-03T09:00:00Z");
        Instant end = Instant.parse("2026-08-03T10:00:00Z");

        assertThatThrownBy(() -> bookingService.adjustSlot(MEETING_ID, UUID.randomUUID(), start, end, organizerPrincipal))
                .isInstanceOf(MeetingConflictException.class);
    }

    private Meeting mockMeeting() {
        Meeting meeting = Meeting.preReserve(
                TENANT_ID, "Title", Instant.now(), Instant.now().plusSeconds(3600), 30, "evt-1", null,
                ORGANIZER_ID, Instant.now());
        return setId(meeting);
    }

    private Meeting setId(final Meeting meeting) {
        try {
            java.lang.reflect.Field idField = Meeting.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(meeting, MEETING_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return meeting;
    }
}
