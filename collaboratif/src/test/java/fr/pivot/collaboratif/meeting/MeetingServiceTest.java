package fr.pivot.collaboratif.meeting;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MeetOpsModuleDisabledException;
import fr.pivot.collaboratif.exception.MeetingTeamNotFoundException;
import fr.pivot.collaboratif.meeting.dto.AgendaItemRequest;
import fr.pivot.collaboratif.meeting.dto.CreateMeetingRequest;
import fr.pivot.collaboratif.meeting.dto.MeetingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeetingService} covering the AC8 module-gate branch and the AC3
 * reconciliation math — the branches not otherwise easiest to exercise end-to-end (the real
 * {@link DefaultMeetOpsModuleCheck} bean in the IT Spring context always returns {@code true}).
 *
 * <p>All external dependencies (repository, module check) are mocked via Mockito, mirroring
 * {@code BoardServiceTest}. No Spring context is loaded — tests are fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
class MeetingServiceTest {

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private MeetOpsModuleCheck moduleCheck;

    private MeetingService meetingService;

    private static final Long USER_A = 1L;
    private static final Long TENANT_A = 100L;

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        meetingService = new MeetingService(meetingRepository, moduleCheck);
    }

    private CollaboratifRequestPrincipal principal() {
        return new CollaboratifRequestPrincipal(USER_A, TENANT_A, "ROLE_USER");
    }

    private CreateMeetingRequest request(final Long teamId, final List<AgendaItemRequest> agendaItems) {
        return new CreateMeetingRequest(
                "Title", Instant.parse("2026-08-01T10:00:00Z"), 30, teamId, agendaItems);
    }

    // -------------------------------------------------------------------------
    // AC8 — module gate
    // -------------------------------------------------------------------------

    @Test
    void create_whenModuleDisabled_throwsMeetOpsModuleDisabledExceptionAndNeverPersists() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> meetingService.create(request(null, List.of()), principal()))
                .isInstanceOf(MeetOpsModuleDisabledException.class);

        verify(meetingRepository, never()).save(any(Meeting.class));
    }

    // -------------------------------------------------------------------------
    // AC7 — teamId cross-tenant/unknown
    // -------------------------------------------------------------------------

    @Test
    void create_whenTeamDoesNotBelongToTenant_throwsMeetingTeamNotFoundExceptionAndNeverPersists() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        when(meetingRepository.teamBelongsToTenant(42L, TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> meetingService.create(request(42L, List.of()), principal()))
                .isInstanceOf(MeetingTeamNotFoundException.class);

        verify(meetingRepository, never()).save(any(Meeting.class));
    }

    // -------------------------------------------------------------------------
    // AC3 — duration reconciliation math
    // -------------------------------------------------------------------------

    @Test
    void create_withMismatchedAgendaDurations_computesSignedDelta() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        List<AgendaItemRequest> items = List.of(
                new AgendaItemRequest("A", 10, "INFO", null),
                new AgendaItemRequest("B", 15, "DISCUSSION", null));

        MeetingResponse response = meetingService.create(request(null, items), principal());

        assertThat(response.agendaDurationMismatch()).isNotNull();
        assertThat(response.agendaDurationMismatch().expectedMinutes()).isEqualTo(30);
        assertThat(response.agendaDurationMismatch().sumMinutes()).isEqualTo(25);
        assertThat(response.agendaDurationMismatch().deltaMinutes()).isEqualTo(-5);
    }

    @Test
    void create_withMatchingAgendaDurations_omitsMismatch() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        List<AgendaItemRequest> items = List.of(new AgendaItemRequest("A", 30, "INFO", null));

        MeetingResponse response = meetingService.create(request(null, items), principal());

        assertThat(response.agendaDurationMismatch()).isNull();
    }

    @Test
    void create_withNoAgendaItems_omitsMismatchRegardlessOfTotalDuration() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));

        MeetingResponse response = meetingService.create(request(null, List.of()), principal());

        assertThat(response.agendaDurationMismatch()).isNull();
        assertThat(response.agendaItems()).isEmpty();
    }

    @Test
    void create_withNullAgendaItems_isTreatedAsEmptyAgenda() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));

        MeetingResponse response = meetingService.create(request(null, null), principal());

        assertThat(response.agendaDurationMismatch()).isNull();
        assertThat(response.agendaItems()).isEmpty();
    }

    @Test
    void create_usesPrincipalTenantAndUserId_neverAnythingFromTheRequest() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        when(meetingRepository.save(any(Meeting.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<Meeting> captor = ArgumentCaptor.forClass(Meeting.class);

        meetingService.create(request(null, List.of()), principal());

        verify(meetingRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT_A);
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(USER_A);
        verify(moduleCheck).isEnabled(TENANT_A);
        verify(meetingRepository, never()).teamBelongsToTenant(anyLong(), anyLong());
    }
}
