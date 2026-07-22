package fr.pivot.agilite.capacity;

import fr.pivot.agilite.auth.entity.PlatformTeam;
import fr.pivot.agilite.capacity.dto.VelocityForecastResponse;
import fr.pivot.agilite.exception.CapacityValidationException;
import fr.pivot.agilite.team.TeamMembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CapacityVelocityForecastService} (US11.6.3), focused on the window
 * validation, access-check delegation, and the {@code NO_HISTORY}/{@code HISTORY} branches that
 * are awkward to exercise exhaustively via full Testcontainers IT — see {@link
 * CapacityEngineControllerIT} for the end-to-end path via {@link CapacitySummaryService}.
 */
@ExtendWith(MockitoExtension.class)
class CapacityVelocityForecastServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long CALLER_USER_ID = 100L;

    @Mock
    private CapacityEventRepository eventRepository;

    @Mock
    private CapacityEventMemberRepository memberRepository;

    @Mock
    private CapacityAbsenceRepository absenceRepository;

    @Mock
    private CapacityHolidayService holidayService;

    @Mock
    private TeamMembershipService teamMembershipService;

    private CapacityVelocityForecastService service;

    @BeforeEach
    void setUp() {
        service = new CapacityVelocityForecastService(
                eventRepository, memberRepository, absenceRepository, holidayService, teamMembershipService);
        PlatformTeam team = mockTeam();
        lenient().when(teamMembershipService.resolveTeamForCaller(TEAM_ID, CALLER_USER_ID, TENANT_ID))
                .thenReturn(team);
        lenient().when(holidayService.holidayDatesForTenant(TENANT_ID)).thenReturn(Set.of());
    }

    @Test
    void forecast_defaultWindow_usesThree() {
        when(eventRepository.findAllByTeamIdAndTypeAndCompletedPointsIsNotNullOrderByEndDateDesc(
                TEAM_ID, CapacityEventType.SPRINT))
                .thenReturn(List.of());

        VelocityForecastResponse response = service.forecast(TEAM_ID, null, CALLER_USER_ID, TENANT_ID);

        assertThat(response.basis()).isEqualTo("NO_HISTORY");
        assertThat(response.avgVelocity()).isNull();
        assertThat(response.confidenceInterval()).isNull();
    }

    @Test
    void forecast_windowBelowMinimum_throwsInvalidVelocityWindow() {
        assertThatThrownBy(() -> service.forecast(TEAM_ID, 0, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(CapacityValidationException.class)
                .satisfies(ex -> assertThat(((CapacityValidationException) ex).getCode()).isEqualTo("INVALID_VELOCITY_WINDOW"));
    }

    @Test
    void forecast_windowAboveMaximum_throwsInvalidVelocityWindow() {
        assertThatThrownBy(() -> service.forecast(TEAM_ID, 11, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(CapacityValidationException.class)
                .satisfies(ex -> assertThat(((CapacityValidationException) ex).getCode()).isEqualTo("INVALID_VELOCITY_WINDOW"));
    }

    @Test
    void forecast_noCompletedSprints_returnsNoHistoryBasis() {
        when(eventRepository.findAllByTeamIdAndTypeAndCompletedPointsIsNotNullOrderByEndDateDesc(
                TEAM_ID, CapacityEventType.SPRINT))
                .thenReturn(List.of());

        VelocityForecastResponse response = service.forecast(TEAM_ID, 3, CALLER_USER_ID, TENANT_ID);

        assertThat(response.basis()).isEqualTo("NO_HISTORY");
    }

    @Test
    void forecast_withCompletedSprintHistory_computesWeightedAverageVelocity() {
        // Mon 2026-01-05 .. Fri 2026-01-16: 10 working days, one member at 100% availability,
        // no absences, no holidays -> 10 net person-days. completedPoints=20 -> velocity 2.0.
        CapacityEvent sprint = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 16), CALLER_USER_ID);
        sprint.setCompletedPoints(20);
        UUID sprintId = UUID.randomUUID();
        setEntityId(sprint, sprintId);

        CapacityEventMember member = new CapacityEventMember(sprintId, 200L, "Alice");
        setEntityId(member, UUID.randomUUID());

        when(eventRepository.findAllByTeamIdAndTypeAndCompletedPointsIsNotNullOrderByEndDateDesc(
                TEAM_ID, CapacityEventType.SPRINT))
                .thenReturn(List.of(sprint));
        when(memberRepository.findAllByEventIdOrderByNameAsc(sprintId)).thenReturn(List.of(member));
        when(absenceRepository.findAllByEventMemberIdIn(List.of(member.getId()))).thenReturn(List.of());

        VelocityForecastResponse response = service.forecast(TEAM_ID, 3, CALLER_USER_ID, TENANT_ID);

        assertThat(response.basis()).isEqualTo("HISTORY");
        assertThat(response.avgVelocity()).isEqualTo(2.0, offset(0.001));
        assertThat(response.confidenceInterval()).isEqualTo("NARROW");
    }

    @Test
    void forecast_teamAccessDenied_propagatesFromTeamMembershipService() {
        Long otherTeamId = 999L;
        when(teamMembershipService.resolveTeamForCaller(otherTeamId, CALLER_USER_ID, TENANT_ID))
                .thenThrow(new RuntimeException("not a member"));

        assertThatThrownBy(() -> service.forecast(otherTeamId, 3, CALLER_USER_ID, TENANT_ID))
                .isInstanceOf(RuntimeException.class);
    }

    private static PlatformTeam mockTeam() {
        PlatformTeam team = mock(PlatformTeam.class);
        lenient().when(team.getId()).thenReturn(TEAM_ID);
        return team;
    }

    private static void setEntityId(final Object entity, final UUID id) {
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", id);
    }
}
