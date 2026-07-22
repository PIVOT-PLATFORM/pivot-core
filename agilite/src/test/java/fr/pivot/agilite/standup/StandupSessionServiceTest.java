package fr.pivot.agilite.standup;

import fr.pivot.agilite.auth.entity.PlatformTeam;
import fr.pivot.agilite.auth.entity.PlatformTeamMember;
import fr.pivot.agilite.auth.entity.PlatformUser;
import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.auth.repository.PlatformUserReadRepository;
import fr.pivot.agilite.exception.StandupConflictException;
import fr.pivot.agilite.exception.StandupSessionNotFoundException;
import fr.pivot.agilite.exception.StandupValidationException;
import fr.pivot.agilite.standup.dto.StandupSessionResponse;
import fr.pivot.agilite.team.TeamMembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StandupSessionService} (US10.1.1/US10.2.2), focused on validation branches
 * that are awkward to exercise exhaustively via full Testcontainers IT — see {@link
 * StandupSessionControllerIT} for the end-to-end happy/error-path coverage.
 */
@ExtendWith(MockitoExtension.class)
class StandupSessionServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long CALLER_USER_ID = 100L;
    private static final Instant NOW = Instant.parse("2026-07-22T10:00:00Z");

    @Mock
    private StandupSessionRepository sessionRepository;

    @Mock
    private StandupParticipantRepository participantRepository;

    @Mock
    private TeamMembershipService teamMembershipService;

    @Mock
    private PlatformTeamMemberReadRepository teamMemberRepository;

    @Mock
    private PlatformUserReadRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private StandupSessionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new StandupSessionService(
                sessionRepository, participantRepository, teamMembershipService,
                teamMemberRepository, userRepository, messagingTemplate, clock);
        lenient().when(sessionRepository.save(any(StandupSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PlatformTeam team = mockTeam(TEAM_ID);
        lenient().when(teamMembershipService.resolveTeamForCaller(TEAM_ID, CALLER_USER_ID, TENANT_ID))
                .thenReturn(team);
    }

    @Test
    void create_withBlankName_throwsInvalidName() {
        assertThatThrownBy(() -> service.create(TEAM_ID, "   ", null, List.of(1L), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode()).isEqualTo("INVALID_NAME"));
    }

    @Test
    void create_withNameOver100Chars_throwsInvalidName() {
        String tooLong = "x".repeat(101);
        assertThatThrownBy(() -> service.create(TEAM_ID, tooLong, null, List.of(1L), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode()).isEqualTo("INVALID_NAME"));
    }

    @Test
    void create_withEmptyParticipants_throwsEmptyParticipants() {
        assertThatThrownBy(() -> service.create(TEAM_ID, "Daily", null, List.of(), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode())
                        .isEqualTo("EMPTY_PARTICIPANTS"));
    }

    @Test
    void create_withTimePerPersonBelowMinimum_throwsInvalidTimePerPerson() {
        assertThatThrownBy(() -> service.create(TEAM_ID, "Daily", 29, List.of(1L), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode())
                        .isEqualTo("INVALID_TIME_PER_PERSON"));
    }

    @Test
    void create_withTimePerPersonAboveMaximum_throwsInvalidTimePerPerson() {
        assertThatThrownBy(() -> service.create(TEAM_ID, "Daily", 1801, List.of(1L), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode())
                        .isEqualTo("INVALID_TIME_PER_PERSON"));
    }

    @Test
    void create_withOmittedTimePerPerson_defaultsTo120() {
        stubParticipant(1L, 200L, "Ada Lovelace");

        StandupSessionResponse response =
                service.create(TEAM_ID, "Daily", null, List.of(1L), CALLER_USER_ID, TENANT_ID);

        assertThat(response.timePerPersonSeconds()).isEqualTo(120);
    }

    @Test
    void create_preservesRequestedParticipantOrder() {
        stubParticipant(1L, 200L, "Ada Lovelace");
        stubParticipant(2L, 201L, "Bob Martin");

        StandupSessionResponse response =
                service.create(TEAM_ID, "Daily", null, List.of(2L, 1L), CALLER_USER_ID, TENANT_ID);

        assertThat(response.participants()).hasSize(2);
        assertThat(response.participants().get(0).teamMemberId()).isEqualTo(2L);
        assertThat(response.participants().get(0).order()).isEqualTo(0);
        assertThat(response.participants().get(0).name()).isEqualTo("Bob Martin");
        assertThat(response.participants().get(1).teamMemberId()).isEqualTo(1L);
        assertThat(response.participants().get(1).order()).isEqualTo(1);
    }

    @Test
    void create_withParticipantNotInTeam_throwsInvalidParticipant() {
        when(teamMemberRepository.findByIdAndTeamId(1L, TEAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(TEAM_ID, "Daily", null, List.of(1L), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode())
                        .isEqualTo("INVALID_PARTICIPANT"));
    }

    @Test
    void getById_whenSessionNotFound_throwsStandupSessionNotFound() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndTenantId(sessionId, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(sessionId, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupSessionNotFoundException.class);
    }

    @Test
    void delete_runningSession_throwsSessionRunningConflict() {
        StandupSession session = newSession(StandupSessionStatus.RUNNING);
        when(sessionRepository.findByIdAndTenantId(session.getId(), TENANT_ID)).thenReturn(Optional.of(session));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, CALLER_USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(session.getId(), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupConflictException.class)
                .satisfies(ex -> assertThat(((StandupConflictException) ex).getCode()).isEqualTo("SESSION_RUNNING"));
    }

    @Test
    void start_onNonPendingSession_throwsInvalidSessionStatus() {
        StandupSession session = newSession(StandupSessionStatus.RUNNING);
        when(sessionRepository.findByIdAndTenantId(session.getId(), TENANT_ID)).thenReturn(Optional.of(session));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, CALLER_USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.start(session.getId(), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupConflictException.class)
                .satisfies(ex -> assertThat(((StandupConflictException) ex).getCode())
                        .isEqualTo("INVALID_SESSION_STATUS"));
    }

    @Test
    void extend_withDisallowedSeconds_throwsInvalidExtendSeconds() {
        UUID sessionId = UUID.randomUUID();

        assertThatThrownBy(() -> service.extend(sessionId, 45, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode())
                        .isEqualTo("INVALID_EXTEND_SECONDS"));
    }

    @Test
    void reorder_withIdNotInWaitingSet_throwsInvalidReorder() {
        StandupSession session = newSession(StandupSessionStatus.RUNNING);
        StandupParticipant waiting = new StandupParticipant(session, 1L, "Ada", 0);
        waiting.setStatus(StandupParticipantStatus.WAITING);
        ReflectionTestUtils.setField(waiting, "id", UUID.randomUUID());
        session.getParticipants().add(waiting);
        when(sessionRepository.findByIdAndTenantId(session.getId(), TENANT_ID)).thenReturn(Optional.of(session));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, CALLER_USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.reorder(
                        session.getId(), List.of(UUID.randomUUID()), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode()).isEqualTo("INVALID_REORDER"));
    }

    @Test
    void reorder_withDuplicateId_throwsInvalidReorder() {
        StandupSession session = newSession(StandupSessionStatus.RUNNING);
        StandupParticipant w1 = new StandupParticipant(session, 1L, "Ada", 0);
        w1.setStatus(StandupParticipantStatus.WAITING);
        ReflectionTestUtils.setField(w1, "id", UUID.randomUUID());
        StandupParticipant w2 = new StandupParticipant(session, 2L, "Bob", 1);
        w2.setStatus(StandupParticipantStatus.WAITING);
        ReflectionTestUtils.setField(w2, "id", UUID.randomUUID());
        session.getParticipants().add(w1);
        session.getParticipants().add(w2);
        when(sessionRepository.findByIdAndTenantId(session.getId(), TENANT_ID)).thenReturn(Optional.of(session));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, CALLER_USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.reorder(
                        session.getId(), List.of(w1.getId(), w1.getId()), CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(StandupValidationException.class)
                .satisfies(ex -> assertThat(((StandupValidationException) ex).getCode()).isEqualTo("INVALID_REORDER"));
    }

    @Test
    void isAccessibleTo_whenSessionExistsAndCallerIsTeamMember_returnsTrue() {
        StandupSession session = newSession(StandupSessionStatus.PENDING);
        when(sessionRepository.findByIdAndTenantId(session.getId(), TENANT_ID)).thenReturn(Optional.of(session));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, CALLER_USER_ID)).thenReturn(true);

        assertThat(service.isAccessibleTo(session.getId(), CALLER_USER_ID, TENANT_ID)).isTrue();
    }

    @Test
    void isAccessibleTo_whenSessionDoesNotExist_returnsFalse() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findByIdAndTenantId(sessionId, TENANT_ID)).thenReturn(Optional.empty());

        assertThat(service.isAccessibleTo(sessionId, CALLER_USER_ID, TENANT_ID)).isFalse();
    }

    private void stubParticipant(final Long teamMemberId, final Long userId, final String displayName) {
        PlatformTeamMember member = mockTeamMember(teamMemberId, TEAM_ID, userId);
        PlatformUser user = mockUser(userId);
        lenient().when(teamMemberRepository.findByIdAndTeamId(teamMemberId, TEAM_ID)).thenReturn(Optional.of(member));
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(teamMembershipService.resolveDisplayName(user)).thenReturn(displayName);
    }

    private static StandupSession newSession(final StandupSessionStatus status) {
        StandupSession session = new StandupSession(TENANT_ID, TEAM_ID, "Daily", 120, CALLER_USER_ID, NOW);
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(session, "status", status);
        return session;
    }

    private static PlatformTeam mockTeam(final Long id) {
        PlatformTeam team = org.mockito.Mockito.mock(PlatformTeam.class);
        lenient().when(team.getId()).thenReturn(id);
        return team;
    }

    private static PlatformTeamMember mockTeamMember(final Long id, final Long teamId, final Long userId) {
        PlatformTeamMember member = org.mockito.Mockito.mock(PlatformTeamMember.class);
        lenient().when(member.getId()).thenReturn(id);
        lenient().when(member.getTeamId()).thenReturn(teamId);
        lenient().when(member.getUserId()).thenReturn(userId);
        return member;
    }

    private static PlatformUser mockUser(final Long id) {
        PlatformUser user = org.mockito.Mockito.mock(PlatformUser.class);
        lenient().when(user.getId()).thenReturn(id);
        return user;
    }
}
