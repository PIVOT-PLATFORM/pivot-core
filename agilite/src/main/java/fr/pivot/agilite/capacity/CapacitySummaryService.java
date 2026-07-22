package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CapacitySummaryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for computing a capacity event's summary (US11.1.2), including PI/Increment
 * aggregation over children (US11.3.1) and the full F11.6 engine's adjustments (US11.6.1
 * holidays, US11.6.2 focus factor, US11.6.3 velocity forecast, US11.6.4 maturity margin, US11.6.5
 * consolidation) — the single orchestration point for the whole calculation, no endpoint
 * reimplements its own formula (US11.6.5 §Notes d'implémentation).
 */
@Service
@Transactional(readOnly = true)
public class CapacitySummaryService {

    private final CapacityEventService eventService;
    private final CapacityEventRepository eventRepository;
    private final CapacityEventMemberRepository memberRepository;
    private final CapacityAbsenceRepository absenceRepository;
    private final CapacityHolidayService holidayService;
    private final CapacityTeamMaturityService maturityService;
    private final CapacityVelocityForecastService velocityForecastService;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventService             shared event access resolution
     * @param eventRepository          repository for event persistence
     * @param memberRepository         repository for roster member persistence
     * @param absenceRepository        repository for absence persistence
     * @param holidayService           tenant holiday resolution (US11.6.1)
     * @param maturityService          team maturity/default resolution (US11.6.4)
     * @param velocityForecastService  velocity forecast resolution (US11.6.3)
     */
    public CapacitySummaryService(
            final CapacityEventService eventService,
            final CapacityEventRepository eventRepository,
            final CapacityEventMemberRepository memberRepository,
            final CapacityAbsenceRepository absenceRepository,
            final CapacityHolidayService holidayService,
            final CapacityTeamMaturityService maturityService,
            final CapacityVelocityForecastService velocityForecastService) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.memberRepository = memberRepository;
        this.absenceRepository = absenceRepository;
        this.holidayService = holidayService;
        this.maturityService = maturityService;
        this.velocityForecastService = velocityForecastService;
    }

    /**
     * Computes an event's capacity summary — a leaf computation for {@code SPRINT}/{@code
     * RELEASE}/{@code CUSTOM} events, an aggregation over non-IP-iteration children for {@code
     * PI_PLANNING}/{@code INCREMENT} events (US11.1.2/US11.3.1/US11.5.1).
     *
     * @param eventId      the event UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the computed summary
     */
    public CapacitySummaryResponse getSummary(final UUID eventId, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = eventService.resolveForCaller(eventId, callerUserId, tenantId);
        boolean parentType = event.getType() == CapacityEventType.PI_PLANNING || event.getType() == CapacityEventType.INCREMENT;
        CapacityCalculator.Summary summary = parentType ? summarizeParent(event, tenantId) : summarizeLeaf(event, tenantId);

        Double forecastPoints = null;
        Double engagementRecommendedPoints = null;
        CapacityMaturityDefaults.Defaults defaults = CapacityMaturityDefaults.forMaturity(
                maturityService.resolveMaturity(event.getTeamId(), tenantId));
        if (!parentType && event.getType() == CapacityEventType.SPRINT && event.getCompletedPoints() == null) {
            CapacityVelocityForecastCalculator.Forecast forecast =
                    velocityForecastService.forecastForTeam(event.getTeamId(), tenantId, 3);
            if (forecast.avgVelocity() != null) {
                forecastPoints = round2(summary.netCapacityDays() * forecast.avgVelocity());
                engagementRecommendedPoints = round2(forecastPoints * (1 - defaults.marginPercent() / 100.0));
            }
        }

        return new CapacitySummaryResponse(
                summary.durationDays(),
                summary.workingDays(),
                summary.memberCount(),
                summary.totalAbsenceDays(),
                summary.netCapacityDays(),
                summary.netCapacityPoints(),
                summary.isProvisional(),
                defaults.marginPercent(),
                defaults.focusFactorPercent(),
                maturityService.resolveMaturity(event.getTeamId(), tenantId) != null ? "TEAM_MATURITY" : "DEFAULT",
                forecastPoints,
                engagementRecommendedPoints);
    }

    /**
     * Computes a leaf event's own summary from its roster, their absences, tenant holidays, and
     * the resolved effective focus factor/maturity — the full F11.6 engine.
     *
     * @param event    the leaf event
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return the computed summary
     */
    private CapacityCalculator.Summary summarizeLeaf(final CapacityEvent event, final Long tenantId) {
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
                        member.getFocusFactorPercent()))
                .toList();

        Set<LocalDate> holidays = holidayService.holidayDatesForTenant(tenantId);
        CapacityMaturityLevel maturity = maturityService.resolveMaturity(event.getTeamId(), tenantId);
        CapacityMaturityDefaults.Defaults defaults = CapacityMaturityDefaults.forMaturity(maturity);
        int eventFocusFactor = event.getFocusFactorPercent() != null ? event.getFocusFactorPercent() : defaults.focusFactorPercent();
        // isProvisional flips false once the tenant/team has genuinely configured something —
        // holidays, a team maturity, or an explicit event/member focus factor (US11.6.5
        // §Architecture) — never true "no work at all was done to configure this event".
        boolean anyMemberFocusOverride = inputs.stream().anyMatch(input -> input.focusFactorPercent() != null);
        boolean isProvisional = holidays.isEmpty() && maturity == null && event.getFocusFactorPercent() == null && !anyMemberFocusOverride;

        return CapacityCalculator.summarize(
                event.getStartDate(), event.getEndDate(), inputs, event.getPointsPerDay(),
                holidays, eventFocusFactor, isProvisional);
    }

    /**
     * Computes a PI Planning/Increment event's aggregated summary from its non-IP-iteration
     * children's own summaries (US11.3.1/US11.5.1).
     *
     * @param parentEvent the PI Planning/Increment event
     * @param tenantId    the calling tenant's {@code public.tenants.id}
     * @return the aggregated summary
     */
    private CapacityCalculator.Summary summarizeParent(final CapacityEvent parentEvent, final Long tenantId) {
        List<CapacityEvent> children = eventRepository.findAllByParentEventIdOrderByStartDateAsc(parentEvent.getId());
        List<CapacityCalculator.Summary> childSummaries = children.stream()
                .filter(child -> !child.isIpIteration())
                .map(child -> summarizeLeaf(child, tenantId))
                .toList();
        return CapacityCalculator.aggregate(parentEvent.getStartDate(), parentEvent.getEndDate(), childSummaries);
    }

    private static double round2(final double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
