package fr.pivot.collaboratif.meeting;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.MeetingForbiddenException;
import fr.pivot.collaboratif.exception.MeetingNotFoundException;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeetingAccessService} (US12.2.1 AC-S1/AC-S2) — the tenant-first,
 * owner-or-admin-second resolution order is the crux of this class's contract: a cross-tenant
 * meeting must 404 <em>regardless of role</em>, and only a same-tenant, non-owner/non-admin
 * caller ever sees the distinct 403.
 */
@ExtendWith(MockitoExtension.class)
class MeetingAccessServiceTest {

    private static final Long TENANT_A = 100L;
    private static final Long TENANT_B = 200L;
    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long TEAM_ID = 55L;
    private static final UUID MEETING_ID = UUID.randomUUID();

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private TeamMemberRepository teamMemberRepository;

    private MeetingAccessService accessService;

    @BeforeEach
    void setUp() {
        accessService = new MeetingAccessService(meetingRepository, teamMemberRepository);
    }

    private Meeting meeting(final Long tenantId, final Long teamId, final Long createdBy) {
        Meeting meeting = new Meeting(tenantId, teamId, "Title", Instant.now(), 30, createdBy, Instant.now());
        ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        return meeting;
    }

    private CollaboratifRequestPrincipal principal(final Long userId, final Long tenantId, final String role) {
        return new CollaboratifRequestPrincipal(userId, tenantId, role);
    }

    // -------------------------------------------------------------------------
    // AC-S1 — tenant isolation, always 404
    // -------------------------------------------------------------------------

    @Test
    void resolveForCaller_whenMeetingDoesNotExist_throwsNotFound() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessService.resolveMeetingForCaller(
                MEETING_ID, principal(OWNER_ID, TENANT_A, "ROLE_USER")))
                .isInstanceOf(MeetingNotFoundException.class);
    }

    @Test
    void resolveForCaller_whenMeetingBelongsToAnotherTenant_throwsNotFound() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(TENANT_B, null, OWNER_ID)));

        assertThatThrownBy(() -> accessService.resolveMeetingForCaller(
                MEETING_ID, principal(OWNER_ID, TENANT_A, "ROLE_USER")))
                .isInstanceOf(MeetingNotFoundException.class);
    }

    @Test
    void resolveForOwnerOrAdmin_whenMeetingBelongsToAnotherTenant_throwsNotFoundEvenForAdmin() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(TENANT_B, null, OWNER_ID)));

        // AC-S1: tenant check runs BEFORE the role check — even ROLE_ADMIN cannot bypass it.
        assertThatThrownBy(() -> accessService.resolveMeetingForOwnerOrAdmin(
                MEETING_ID, principal(999L, TENANT_A, "ROLE_ADMIN")))
                .isInstanceOf(MeetingNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // AC-S2 — owner-or-admin, distinct 403 once tenant is confirmed
    // -------------------------------------------------------------------------

    @Test
    void resolveForOwnerOrAdmin_whenCallerIsTheOwner_resolves() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(TENANT_A, null, OWNER_ID)));

        Meeting resolved = accessService.resolveMeetingForOwnerOrAdmin(
                MEETING_ID, principal(OWNER_ID, TENANT_A, "ROLE_USER"));

        assertThat(resolved.getId()).isEqualTo(MEETING_ID);
    }

    @Test
    void resolveForOwnerOrAdmin_whenCallerIsAdmin_resolvesEvenIfNotOwner() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(TENANT_A, null, OWNER_ID)));

        Meeting resolved = accessService.resolveMeetingForOwnerOrAdmin(
                MEETING_ID, principal(OTHER_USER_ID, TENANT_A, "ROLE_ADMIN"));

        assertThat(resolved.getId()).isEqualTo(MEETING_ID);
    }

    @Test
    void resolveForOwnerOrAdmin_whenCallerIsAParticipantButNotOwnerOrAdmin_throwsForbidden() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(TENANT_A, null, OWNER_ID)));

        assertThatThrownBy(() -> accessService.resolveMeetingForOwnerOrAdmin(
                MEETING_ID, principal(OTHER_USER_ID, TENANT_A, "ROLE_USER")))
                .isInstanceOf(MeetingForbiddenException.class)
                .extracting(ex -> ((MeetingForbiddenException) ex).getCode())
                .isEqualTo("NOT_MEETING_OWNER");
    }

    // -------------------------------------------------------------------------
    // AC-07 — participant visibility (team membership)
    // -------------------------------------------------------------------------

    @Test
    void resolveForCaller_whenCallerIsATeamMember_resolves() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(TENANT_A, TEAM_ID, OWNER_ID)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, OTHER_USER_ID))
                .thenReturn(Optional.of(mock(TeamMember.class)));

        Meeting resolved = accessService.resolveMeetingForCaller(
                MEETING_ID, principal(OTHER_USER_ID, TENANT_A, "ROLE_USER"));

        assertThat(resolved.getId()).isEqualTo(MEETING_ID);
    }

    @Test
    void resolveForCaller_whenCallerIsNeitherOwnerNorTeamMember_throwsNotFound() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(TENANT_A, TEAM_ID, OWNER_ID)));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accessService.resolveMeetingForCaller(
                MEETING_ID, principal(OTHER_USER_ID, TENANT_A, "ROLE_USER")))
                .isInstanceOf(MeetingNotFoundException.class);
    }

    @Test
    void resolveForCaller_whenNoTeam_neverConsultsTeamMemberRepository() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting(TENANT_A, null, OWNER_ID)));

        accessService.resolveMeetingForCaller(MEETING_ID, principal(OWNER_ID, TENANT_A, "ROLE_USER"));

        verify(teamMemberRepository, never()).findByTeamIdAndUserId(any(), any());
    }
}
