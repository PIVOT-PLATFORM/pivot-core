package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.calc.CapacityCalculator;
import fr.pivot.agilite.capacity.dto.CapacityAbsenceInput;
import fr.pivot.agilite.capacity.dto.CapacityEventInput;
import fr.pivot.agilite.capacity.dto.CapacityGaugeResponse;
import fr.pivot.agilite.capacity.dto.CapacityMemberBreakdownResponse;
import fr.pivot.agilite.capacity.dto.CapacityMemberInput;
import fr.pivot.agilite.capacity.dto.CapacitySummaryResponse;
import fr.pivot.agilite.capacity.dto.EventCapacityResult;
import fr.pivot.agilite.capacity.dto.MemberCapacityResult;
import fr.pivot.agilite.capacity.dto.PiCapacityResult;
import fr.pivot.agilite.capacity.dto.SprintContribution;
import fr.pivot.agilite.capacity.exception.CapacityEventNotFoundException;
import fr.pivot.core.team.TeamMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic reading a persisted {@link CapacityEvent} (and its members/absences/children)
 * and driving the pure {@code CapacityCalculator} to build its summary, PI consolidation, and
 * engagement gauge (F11.6.5 + F11.6.6 — E11 capacity planning).
 *
 * <p>Team existence/tenant-ownership is enforced via {@link CapacityEventRepository#findByIdAndTenantId},
 * caller membership directly against {@code fr.pivot.core.team.TeamMemberRepository}, exported
 * as-is by {@code pivot-core-starter} — same inline access-check pattern as {@code
 * fr.pivot.agilite.retro.session.RetroSessionService}. Read-only: membership alone suffices, no
 * role gate.
 */
@Service
@Transactional(readOnly = true)
public class CapacitySummaryService {

    /** Fallback working-day set (Mon..Fri) when {@link CapacityEvent#getWorkingDays()} is unset. */
    static final Set<Integer> DEFAULT_WORKING_DAYS = Set.of(1, 2, 3, 4, 5);

    /**
     * Multiplier applied to {@link EventCapacityResult#totalEngagementRecommande()} above which
     * {@link CapacityGaugeResponse#overCommitted()} flips — see {@link CapacityGaugeResponse}'s
     * Javadoc for the denominator/threshold rationale.
     */
    static final double GAUGE_OVERFLOW_THRESHOLD = 1.0;

    private final CapacityEventRepository eventRepository;
    private final CapacityEventMemberRepository eventMemberRepository;
    private final CapacityAbsenceRepository absenceRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param eventRepository       capacity event persistence
     * @param eventMemberRepository capacity event member persistence
     * @param absenceRepository     capacity absence persistence
     * @param teamMemberRepository  {@code pivot-core-starter}'s team membership persistence
     */
    public CapacitySummaryService(
            final CapacityEventRepository eventRepository,
            final CapacityEventMemberRepository eventMemberRepository,
            final CapacityAbsenceRepository absenceRepository,
            final TeamMemberRepository teamMemberRepository) {
        this.eventRepository = eventRepository;
        this.eventMemberRepository = eventMemberRepository;
        this.absenceRepository = absenceRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    /**
     * Builds the full summary — per-member breakdown, event totals, optional PI consolidation,
     * and the engagement gauge — for one capacity event.
     *
     * @param eventId  the event id from the path
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the full summary
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to a different
     *                                         tenant, or the caller is not a member of its team —
     *                                         all collapse to 404
     */
    public CapacitySummaryResponse summarize(final UUID eventId, final Long callerId, final Long tenantId) {
        CapacityEvent event = eventRepository.findByIdAndTenantId(eventId, tenantId)
                .orElseThrow(() -> new CapacityEventNotFoundException(eventId));

        teamMemberRepository.findByTeamIdAndUserId(event.getTeamId(), callerId)
                .orElseThrow(() -> new CapacityEventNotFoundException(eventId));

        List<CapacityEventMember> members = eventMemberRepository.findByEventIdOrderByPositionAsc(eventId);
        EventCapacityResult capacity = computeCapacity(event, members);

        List<CapacityMemberBreakdownResponse> breakdown = toBreakdown(members, capacity);

        PiCapacityResult consolidation = consolidatePiIfApplicable(event, tenantId);

        CapacityGaugeResponse gauge = buildGauge(event, capacity);

        return new CapacitySummaryResponse(
                event.getId(),
                event.getType(),
                event.getName(),
                capacity.totalWorkingDays(),
                breakdown,
                capacity.totalJoursHommeNets(),
                capacity.totalCapaciteNette(),
                capacity.totalPoints(),
                capacity.totalEngagementRecommande(),
                capacity.loadRatio(),
                capacity.predictability(),
                consolidation,
                gauge);
    }

    /**
     * Loads a PI event's direct sprint children (if any) and consolidates their capacity.
     *
     * @param event    the event, possibly a PI (or generically any event with children)
     * @param tenantId the caller's tenant id, used to scope the children lookup
     * @return the consolidated PI capacity, or {@code null} if the event has no children
     */
    private PiCapacityResult consolidatePiIfApplicable(final CapacityEvent event, final Long tenantId) {
        List<CapacityEvent> children = eventRepository.findByParentIdAndTenantId(event.getId(), tenantId);
        if (children.isEmpty()) {
            return null;
        }

        boolean isSafe = children.stream().anyMatch(CapacityEvent::isIpSprint);

        List<SprintContribution> contributions = new ArrayList<>(children.size());
        for (CapacityEvent child : children) {
            List<CapacityEventMember> childMembers = eventMemberRepository.findByEventIdOrderByPositionAsc(child.getId());
            EventCapacityResult childCapacity = computeCapacity(child, childMembers);
            contributions.add(new SprintContribution(childCapacity, child.isIpSprint()));
        }

        return CapacityCalculator.consolidatePi(contributions, isSafe);
    }

    /**
     * Builds the engagement gauge (F11.6.6) — see {@link CapacityGaugeResponse}'s Javadoc for the
     * denominator/threshold rationale.
     *
     * @param event    the event, whose {@code committedPoints} is the engaged value
     * @param capacity the event's computed capacity, whose {@code totalEngagementRecommande} is
     *                 the reference value
     * @return the gauge
     */
    private CapacityGaugeResponse buildGauge(final CapacityEvent event, final EventCapacityResult capacity) {
        double engaged = event.getCommittedPoints() != null ? event.getCommittedPoints() : 0.0;
        double reference = capacity.totalEngagementRecommande();
        Double ratio = reference > 0 ? CapacityCalculator.round2(engaged / reference) : null;
        boolean overCommitted = reference > 0 && engaged > GAUGE_OVERFLOW_THRESHOLD * reference;
        return new CapacityGaugeResponse(engaged, reference, GAUGE_OVERFLOW_THRESHOLD, ratio, overCommitted);
    }

    /**
     * Loads an event's members' absences and drives {@code
     * CapacityCalculator#computeEventCapacity} for it.
     *
     * @param event   the event to compute
     * @param members the event's members, already loaded in display order
     * @return the computed capacity
     */
    private EventCapacityResult computeCapacity(final CapacityEvent event, final List<CapacityEventMember> members) {
        List<UUID> memberIds = members.stream().map(CapacityEventMember::getId).toList();
        Map<UUID, List<CapacityAbsenceInput>> absencesByMember = new HashMap<>();
        if (!memberIds.isEmpty()) {
            for (CapacityAbsence absence : absenceRepository.findByEventMemberIdIn(memberIds)) {
                absencesByMember
                        .computeIfAbsent(absence.getEventMemberId(), key -> new ArrayList<>())
                        .add(new CapacityAbsenceInput(absence.getStartDate(), absence.getEndDate(), absence.getFraction()));
            }
        }

        List<CapacityMemberInput> memberInputs = new ArrayList<>(members.size());
        for (CapacityEventMember member : members) {
            memberInputs.add(new CapacityMemberInput(
                    member.getId().toString(),
                    member.getQuotite(),
                    member.getFocusFactor(),
                    member.getRole(),
                    member.isExcluded(),
                    member.getPosition(),
                    absencesByMember.getOrDefault(member.getId(), List.of())));
        }

        CapacityEventInput input = new CapacityEventInput(
                event.getStartDate(),
                event.getEndDate(),
                workingDaysOf(event),
                // TODO(EN22.3): the calendar connectors resolving a real holiday set (public
                // holidays, tenant-specific closures) are Wave 2 — pass no holidays for now.
                Set.of(),
                event.getFocusFactor(),
                null,
                event.getMargeSecurite(),
                event.getPointsPerDay(),
                event.getMaturityLevel(),
                event.getCommittedPoints(),
                event.getCompletedPoints(),
                memberInputs);

        return CapacityCalculator.computeEventCapacity(input);
    }

    /**
     * Resolves the working-day set to feed the calculator, falling back to Mon..Fri when the
     * event's own {@link CapacityEvent#getWorkingDays()} is {@code null} or empty.
     *
     * @param event the event whose working days to resolve
     * @return the resolved, never-empty working-day set
     */
    private static Set<Integer> workingDaysOf(final CapacityEvent event) {
        Integer[] workingDays = event.getWorkingDays();
        if (workingDays == null || workingDays.length == 0) {
            return DEFAULT_WORKING_DAYS;
        }
        return Set.of(workingDays);
    }

    /**
     * Zips the computed {@link MemberCapacityResult}s back to their {@link CapacityEventMember}
     * roster snapshot (name/role/quotite), preserving the calculator's position ordering.
     *
     * @param members  the event's members, in display order
     * @param capacity the computed capacity, whose {@code members()} is ordered identically
     * @return the per-member breakdown, in display order
     */
    private static List<CapacityMemberBreakdownResponse> toBreakdown(
            final List<CapacityEventMember> members, final EventCapacityResult capacity) {
        Map<String, CapacityEventMember> byId = new HashMap<>();
        for (CapacityEventMember member : members) {
            byId.put(member.getId().toString(), member);
        }

        List<CapacityMemberBreakdownResponse> breakdown = new ArrayList<>(capacity.members().size());
        for (MemberCapacityResult result : capacity.members()) {
            CapacityEventMember member = byId.get(result.memberId());
            breakdown.add(new CapacityMemberBreakdownResponse(
                    member.getId(),
                    member.getName(),
                    member.getRole(),
                    member.getQuotite(),
                    member.isExcluded(),
                    result.effectiveFocus(),
                    result.absentWorkingDays(),
                    result.joursHommeNets(),
                    result.capaciteNette(),
                    result.points(),
                    result.engagementRecommande()));
        }
        return breakdown;
    }
}
