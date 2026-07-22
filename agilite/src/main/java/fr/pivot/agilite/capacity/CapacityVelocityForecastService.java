package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.VelocityForecastResponse;
import fr.pivot.agilite.exception.CapacityValidationException;
import fr.pivot.agilite.team.TeamMembershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for a team's net-person-day-weighted velocity forecast (US11.6.3).
 *
 * <p>Net person-days here are computed <strong>without</strong> a focus-factor adjustment —
 * consistent with PouetPouet's reference model ({@code computeMemberCapacity}, {@code
 * apps/web/src/lib/capacity.ts}), where realized velocity is expressed per raw available person-
 * day, not per already-focus-adjusted hour. This is a self-contained computation, deliberately not
 * routed through {@link CapacitySummaryService} (which itself depends on this class for {@code
 * forecastPoints}) to avoid a circular dependency.
 */
@Service
@Transactional(readOnly = true)
public class CapacityVelocityForecastService {

    private static final int MIN_WINDOW = 1;
    private static final int MAX_WINDOW = 10;

    private final CapacityEventRepository eventRepository;
    private final CapacityEventMemberRepository memberRepository;
    private final CapacityAbsenceRepository absenceRepository;
    private final CapacityHolidayService holidayService;
    private final TeamMembershipService teamMembershipService;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventRepository       repository for event persistence
     * @param memberRepository      repository for roster member persistence
     * @param absenceRepository     repository for absence persistence
     * @param holidayService        tenant holiday resolution (US11.6.1)
     * @param teamMembershipService shared team-resolution/membership-check helper
     */
    public CapacityVelocityForecastService(
            final CapacityEventRepository eventRepository,
            final CapacityEventMemberRepository memberRepository,
            final CapacityAbsenceRepository absenceRepository,
            final CapacityHolidayService holidayService,
            final TeamMembershipService teamMembershipService) {
        this.eventRepository = eventRepository;
        this.memberRepository = memberRepository;
        this.absenceRepository = absenceRepository;
        this.holidayService = holidayService;
        this.teamMembershipService = teamMembershipService;
    }

    /**
     * Computes a team's moving-average velocity forecast, access-checked (US11.6.3).
     *
     * @param teamId       the team's {@code public.teams.id}
     * @param window       the number of most-recent completed sprints to average, {@code [1, 10]}
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the forecast response
     */
    public VelocityForecastResponse forecast(
            final Long teamId, final Integer window, final Long callerUserId, final Long tenantId) {
        teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
        int effectiveWindow = window != null ? window : 3;
        if (effectiveWindow < MIN_WINDOW || effectiveWindow > MAX_WINDOW) {
            throw new CapacityValidationException("INVALID_VELOCITY_WINDOW", "window must be between 1 and " + MAX_WINDOW);
        }
        CapacityVelocityForecastCalculator.Forecast forecast = forecastForTeam(teamId, tenantId, effectiveWindow);
        return new VelocityForecastResponse(forecast.avgVelocity(), forecast.confidenceInterval(), forecast.basis());
    }

    /**
     * Computes a team's forecast without an access check — internal helper for {@link
     * CapacitySummaryService}, which has already resolved access via its own event.
     *
     * @param teamId   the team's {@code public.teams.id}
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @param window   the number of most-recent completed sprints to average, {@code [1, 10]}
     * @return the computed forecast
     */
    CapacityVelocityForecastCalculator.Forecast forecastForTeam(final Long teamId, final Long tenantId, final int window) {
        List<CapacityEvent> completedSprints = eventRepository
                .findAllByTeamIdAndTypeAndCompletedPointsIsNotNullOrderByEndDateDesc(teamId, CapacityEventType.SPRINT);
        Set<LocalDate> holidays = holidayService.holidayDatesForTenant(tenantId);
        List<CapacityVelocityForecastCalculator.SprintHistoryEntry> history = completedSprints.stream()
                .map(sprint -> new CapacityVelocityForecastCalculator.SprintHistoryEntry(
                        netPersonDaysWithoutFocus(sprint, holidays), sprint.getCompletedPoints()))
                .toList();
        return CapacityVelocityForecastCalculator.forecast(history, window);
    }

    /**
     * Computes a past sprint's net person-days — availability-weighted, holiday-aware, but
     * deliberately without a focus-factor adjustment (see this class's Javadoc).
     *
     * @param event    the completed sprint
     * @param holidays tenant holiday dates to exclude alongside weekends
     * @return the sprint's net capacity in person-days
     */
    private double netPersonDaysWithoutFocus(final CapacityEvent event, final Set<LocalDate> holidays) {
        List<CapacityEventMember> members = memberRepository.findAllByEventIdOrderByNameAsc(event.getId());
        List<UUID> memberIds = members.stream().map(CapacityEventMember::getId).toList();
        Map<UUID, List<CapacityAbsence>> absencesByMember = absenceRepository.findAllByEventMemberIdIn(memberIds)
                .stream()
                .collect(Collectors.groupingBy(CapacityAbsence::getEventMemberId));

        List<CapacityCalculator.MemberInput> inputs = members.stream()
                .map(member -> new CapacityCalculator.MemberInput(
                        member.isExcluded(),
                        member.getAvailabilityPercent(),
                        absencesByMember.getOrDefault(member.getId(), List.of()).stream()
                                .map(absence -> new CapacityCalculator.AbsenceRange(
                                        absence.getDateDebut(), absence.getDateFin()))
                                .toList(),
                        null))
                .toList();

        return CapacityCalculator.summarize(
                        event.getStartDate(), event.getEndDate(), inputs, event.getPointsPerDay(), holidays, 100, true)
                .netCapacityDays();
    }
}
