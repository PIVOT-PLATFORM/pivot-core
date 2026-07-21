package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.connector.HolidayConnector;
import fr.pivot.agilite.capacity.dto.CapacityGaugeResponse;
import fr.pivot.agilite.capacity.dto.CapacityMemberBreakdownResponse;
import fr.pivot.agilite.capacity.dto.CapacitySummaryResponse;
import fr.pivot.agilite.capacity.exception.CapacityEventNotFoundException;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CapacitySummaryService} (F11.6.5 + F11.6.6), mapping persisted entities
 * to {@code CapacityCalculator} inputs and back to the response DTOs.
 *
 * <p>All external dependencies (repositories) are mocked via Mockito. No Spring context is
 * loaded — tests are fast and isolated.
 */
@ExtendWith(MockitoExtension.class)
class CapacitySummaryServiceTest {

    @Mock
    private CapacityEventRepository eventRepository;

    @Mock
    private CapacityEventMemberRepository eventMemberRepository;

    @Mock
    private CapacityAbsenceRepository absenceRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @Mock
    private HolidayConnector holidayConnector;

    private CapacitySummaryService service;

    private static final Long TENANT_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final Long CALLER_ID = 1L;

    @BeforeEach
    void setUp() {
        // lenient: the "not found" tests short-circuit before this connector is ever consulted.
        org.mockito.Mockito.lenient()
                .when(holidayConnector.holidaysFor(any(), any(), any()))
                .thenReturn(Set.of());
        service = new CapacitySummaryService(
                eventRepository, eventMemberRepository, absenceRepository, teamMemberRepository, holidayConnector);
    }

    /**
     * A single-member, one-week sprint with no absences and no points tracked: the summary must
     * expose the same totals as a direct {@code CapacityCalculator} computation (10 working days
     * × 1.0 quotite = 10 joursHommeNets, × 0.70 default focus = 7.0 capaciteNette, × (1 − 0.15
     * default margin) = 5.95 recommended engagement).
     */
    @Test
    void summarize_singleMemberNoOverrides_matchesCalculatorDefaults() {
        UUID eventId = UUID.randomUUID();
        CapacityEvent event = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        setId(event, eventId);

        UUID memberId = UUID.randomUUID();
        CapacityEventMember member = new CapacityEventMember(eventId, null, "Alice", "DEV", 1.0, 0);
        setMemberId(member, memberId);

        when(eventRepository.findByIdAndTenantId(eventId, TENANT_ID)).thenReturn(Optional.of(event));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(eventId)).thenReturn(List.of(member));
        when(absenceRepository.findByEventMemberIdIn(List.of(memberId))).thenReturn(List.of());
        when(eventRepository.findByParentIdAndTenantId(eventId, TENANT_ID)).thenReturn(List.of());

        CapacitySummaryResponse response = service.summarize(eventId, CALLER_ID, TENANT_ID);

        assertThat(response.totalWorkingDays()).isEqualTo(10);
        assertThat(response.totalNetPersonDays()).isEqualTo(10.0);
        assertThat(response.totalNetCapacity()).isEqualTo(7.0);
        assertThat(response.totalRecommendedEngagement()).isEqualTo(5.95);
        assertThat(response.totalPoints()).isNull();
        assertThat(response.consolidation()).isNull();

        assertThat(response.members()).hasSize(1);
        CapacityMemberBreakdownResponse breakdown = response.members().get(0);
        assertThat(breakdown.memberId()).isEqualTo(memberId);
        assertThat(breakdown.name()).isEqualTo("Alice");
        assertThat(breakdown.workedDays()).isEqualTo(10.0);
        assertThat(breakdown.netCapacity()).isEqualTo(7.0);
        assertThat(breakdown.recommendedEngagement()).isEqualTo(5.95);
    }

    /**
     * {@link HolidayConnector} is consulted with the event's own dates and the first member's
     * locality, and a resolved holiday excludes one working day from the totals — proving the
     * wiring actually reaches {@code CapacityCalculator}, not just that the default connector is a
     * no-op.
     */
    @Test
    void summarize_holidayConnectorResolvesADate_excludesItFromWorkingDays() {
        UUID eventId = UUID.randomUUID();
        LocalDate startDate = LocalDate.of(2026, 7, 6);
        LocalDate endDate = LocalDate.of(2026, 7, 17);
        CapacityEvent event = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "Sprint 1",
                startDate, endDate, new Integer[] {1, 2, 3, 4, 5});
        setId(event, eventId);

        UUID memberId = UUID.randomUUID();
        CapacityEventMember member = new CapacityEventMember(eventId, null, "Alice", "DEV", 1.0, 0);
        setMemberId(member, memberId);
        member.setLocality("FR");

        when(eventRepository.findByIdAndTenantId(eventId, TENANT_ID)).thenReturn(Optional.of(event));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(eventId)).thenReturn(List.of(member));
        when(absenceRepository.findByEventMemberIdIn(List.of(memberId))).thenReturn(List.of());
        when(eventRepository.findByParentIdAndTenantId(eventId, TENANT_ID)).thenReturn(List.of());
        LocalDate holiday = LocalDate.of(2026, 7, 14);
        when(holidayConnector.holidaysFor("FR", startDate, endDate)).thenReturn(Set.of(holiday));

        CapacitySummaryResponse response = service.summarize(eventId, CALLER_ID, TENANT_ID);

        assertThat(response.totalWorkingDays()).isEqualTo(9);
    }

    /** No committed points at all: the gauge stays at zero engagement and never over-committed. */
    @Test
    void summarize_noCommittedPoints_gaugeNotOverCommitted() {
        UUID eventId = UUID.randomUUID();
        CapacityEvent event = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        setId(event, eventId);

        when(eventRepository.findByIdAndTenantId(eventId, TENANT_ID)).thenReturn(Optional.of(event));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(eventId)).thenReturn(List.of());
        when(eventRepository.findByParentIdAndTenantId(eventId, TENANT_ID)).thenReturn(List.of());

        CapacitySummaryResponse response = service.summarize(eventId, CALLER_ID, TENANT_ID);

        CapacityGaugeResponse gauge = response.gauge();
        assertThat(gauge.engagedPoints()).isEqualTo(0.0);
        assertThat(gauge.overCommitted()).isFalse();
    }

    /** Committed points beyond the recommended engagement flips the gauge's over-committed flag. */
    @Test
    void summarize_committedPointsAboveRecommendedEngagement_gaugeOverCommitted() {
        UUID eventId = UUID.randomUUID();
        CapacityEvent event = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        setId(event, eventId);
        event.setCommittedPoints(6.0); // recommended engagement is 5.95 with default profile

        when(eventRepository.findByIdAndTenantId(eventId, TENANT_ID)).thenReturn(Optional.of(event));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));

        UUID memberId = UUID.randomUUID();
        CapacityEventMember member = new CapacityEventMember(eventId, null, "Alice", "DEV", 1.0, 0);
        setMemberId(member, memberId);
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(eventId)).thenReturn(List.of(member));
        when(absenceRepository.findByEventMemberIdIn(List.of(memberId))).thenReturn(List.of());
        when(eventRepository.findByParentIdAndTenantId(eventId, TENANT_ID)).thenReturn(List.of());

        CapacitySummaryResponse response = service.summarize(eventId, CALLER_ID, TENANT_ID);

        assertThat(response.totalRecommendedEngagement()).isEqualTo(5.95);
        assertThat(response.gauge().overCommitted()).isTrue();
    }

    /** A PI with sprint children gets its consolidation populated, summing the children's capacity. */
    @Test
    void summarize_piWithChildren_consolidatesSprintContributions() {
        UUID piId = UUID.randomUUID();
        CapacityEvent pi = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.PI_PLANNING, "PI 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 8, 14), new Integer[] {1, 2, 3, 4, 5});
        setId(pi, piId);

        UUID sprintId = UUID.randomUUID();
        CapacityEvent sprint = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        setId(sprint, sprintId);

        UUID memberId = UUID.randomUUID();
        CapacityEventMember member = new CapacityEventMember(sprintId, null, "Alice", "DEV", 1.0, 0);
        setMemberId(member, memberId);

        when(eventRepository.findByIdAndTenantId(piId, TENANT_ID)).thenReturn(Optional.of(pi));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(piId)).thenReturn(List.of());
        when(eventRepository.findByParentIdAndTenantId(piId, TENANT_ID)).thenReturn(List.of(sprint));
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(sprintId)).thenReturn(List.of(member));
        when(absenceRepository.findByEventMemberIdIn(List.of(memberId))).thenReturn(List.of());

        CapacitySummaryResponse response = service.summarize(piId, CALLER_ID, TENANT_ID);

        assertThat(response.consolidation()).isNotNull();
        assertThat(response.consolidation().includedSprintCount()).isEqualTo(1);
        assertThat(response.consolidation().excludedIpSprintCount()).isEqualTo(0);
        assertThat(response.consolidation().totalCapaciteNette()).isEqualTo(7.0);
    }

    /** An IP sprint child is excluded from the consolidated totals (SAFe consolidation). */
    @Test
    void summarize_piWithIpSprintChild_excludesItFromConsolidation() {
        UUID piId = UUID.randomUUID();
        CapacityEvent pi = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.PI_PLANNING, "PI 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 8, 14), new Integer[] {1, 2, 3, 4, 5});
        setId(pi, piId);

        UUID ipSprintId = UUID.randomUUID();
        CapacityEvent ipSprint = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "IP Sprint",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        setId(ipSprint, ipSprintId);
        ipSprint.setIpSprint(true);

        when(eventRepository.findByIdAndTenantId(piId, TENANT_ID)).thenReturn(Optional.of(pi));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(piId)).thenReturn(List.of());
        when(eventRepository.findByParentIdAndTenantId(piId, TENANT_ID)).thenReturn(List.of(ipSprint));
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(ipSprintId)).thenReturn(List.of());

        CapacitySummaryResponse response = service.summarize(piId, CALLER_ID, TENANT_ID);

        assertThat(response.consolidation().includedSprintCount()).isEqualTo(0);
        assertThat(response.consolidation().excludedIpSprintCount()).isEqualTo(1);
        assertThat(response.consolidation().totalCapaciteNette()).isEqualTo(0.0);
    }

    /** Unknown event id → 404, never disclosing whether it exists in another tenant. */
    @Test
    void summarize_unknownEvent_throwsNotFound() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.findByIdAndTenantId(eventId, TENANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.summarize(eventId, CALLER_ID, TENANT_ID))
                .isInstanceOf(CapacityEventNotFoundException.class);
    }

    /** Caller not a member of the event's team → 404, same as an unknown event. */
    @Test
    void summarize_callerNotTeamMember_throwsNotFound() {
        UUID eventId = UUID.randomUUID();
        CapacityEvent event = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), new Integer[] {1, 2, 3, 4, 5});
        setId(event, eventId);

        when(eventRepository.findByIdAndTenantId(eventId, TENANT_ID)).thenReturn(Optional.of(event));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.summarize(eventId, CALLER_ID, TENANT_ID))
                .isInstanceOf(CapacityEventNotFoundException.class);
    }

    /** Null/empty {@code workingDays} falls back to Mon..Fri rather than counting zero days. */
    @Test
    void summarize_nullWorkingDays_fallsBackToMonFri() {
        UUID eventId = UUID.randomUUID();
        CapacityEvent event = new CapacityEvent(
                TENANT_ID, TEAM_ID, CapacityEventType.SPRINT, "Sprint 1",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17), null);
        setId(event, eventId);

        when(eventRepository.findByIdAndTenantId(eventId, TENANT_ID)).thenReturn(Optional.of(event));
        when(teamMemberRepository.findByTeamIdAndUserId(TEAM_ID, CALLER_ID))
                .thenReturn(Optional.of(new TeamMember(TEAM_ID, CALLER_ID)));
        when(eventMemberRepository.findByEventIdOrderByPositionAsc(eventId)).thenReturn(List.of());
        when(eventRepository.findByParentIdAndTenantId(eventId, TENANT_ID)).thenReturn(List.of());

        CapacitySummaryResponse response = service.summarize(eventId, CALLER_ID, TENANT_ID);

        assertThat(response.totalWorkingDays()).isEqualTo(10);
    }

    private static void setId(final CapacityEvent event, final UUID id) {
        try {
            var field = CapacityEvent.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(event, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setMemberId(final CapacityEventMember member, final UUID id) {
        try {
            var field = CapacityEventMember.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(member, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
