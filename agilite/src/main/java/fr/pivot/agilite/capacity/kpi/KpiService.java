package fr.pivot.agilite.capacity.kpi;

import fr.pivot.agilite.capacity.CapacityAbsence;
import fr.pivot.agilite.capacity.CapacityAbsenceRepository;
import fr.pivot.agilite.capacity.CapacityEvent;
import fr.pivot.agilite.capacity.CapacityEventMember;
import fr.pivot.agilite.capacity.CapacityEventMemberRepository;
import fr.pivot.agilite.capacity.CapacityEventRepository;
import fr.pivot.agilite.capacity.CapacityEventType;
import fr.pivot.agilite.capacity.CapacityVelocity;
import fr.pivot.agilite.capacity.CapacityVelocityRepository;
import fr.pivot.agilite.capacity.calc.CapacityCalculator;
import fr.pivot.agilite.capacity.dto.CapacityAbsenceInput;
import fr.pivot.agilite.capacity.dto.CapacityEventInput;
import fr.pivot.agilite.capacity.dto.CapacityMemberInput;
import fr.pivot.agilite.capacity.dto.EventCapacityResult;
import fr.pivot.agilite.capacity.dto.MemberCapacityResult;
import fr.pivot.agilite.capacity.exception.CapacityEventNotFoundException;
import fr.pivot.agilite.capacity.kpi.dto.KpiResponse;
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
 * Computes the five E11 capacity KPIs on demand, straight from the {@code agilite.capacity_*}
 * repositories (EN11.2 — pull model, no persisted/cached KPI rows, no dependency on any other
 * service being edited to feed them).
 *
 * <p>Team existence/tenant-ownership and caller membership are enforced with the exact same
 * inline pattern as {@code fr.pivot.agilite.capacity.CapacitySummaryService#summarize}: the
 * caller supplies one capacity event id belonging to the team to KPI-report on, resolved via
 * {@link CapacityEventRepository#findByIdAndTenantId}, then the caller's membership of that
 * event's team is checked directly against {@code
 * fr.pivot.core.team.TeamMemberRepository#findByTeamIdAndUserId} — both failure branches collapse
 * to {@link CapacityEventNotFoundException} (404), never confirming cross-tenant existence. Once
 * resolved, the KPIs themselves are computed over <strong>every</strong> capacity event of that
 * team/tenant (not just the anchor event), via {@link
 * CapacityEventRepository#findByTeamIdAndTenantId} — team-level aggregation, not a single-event
 * snapshot.
 *
 * <p><strong>RGPD — aggregate only.</strong> Every KPI is a sum/average/count across the team's
 * events and (non-excluded) members; individual {@code CapacityEventMember} rows (which carry a
 * name — see its Javadoc) are read only to sum {@link
 * MemberCapacityResult#absentWorkingDays()}/count headcount, never surfaced in {@link
 * KpiResponse}.
 */
@Service
@Transactional(readOnly = true)
public class KpiService {

    /** Fallback working-day set (Mon..Fri), mirrors {@code CapacitySummaryService}'s default. */
    static final Set<Integer> DEFAULT_WORKING_DAYS = Set.of(1, 2, 3, 4, 5);

    /**
     * Multiplier above which an event's {@code committedPoints} counts as a {@code
     * capacity.depassements} overrun — same value/rationale as {@code
     * CapacitySummaryService.GAUGE_OVERFLOW_THRESHOLD} (F11.6.6's engagement gauge), duplicated
     * here rather than reused because that field is package-private to {@code
     * fr.pivot.agilite.capacity} and this is a new, non-edited file in the {@code .capacity.kpi}
     * sub-package.
     */
    static final double OVER_COMMIT_THRESHOLD = 1.0;

    /** KPI key for the team's utilization rate, in percent. */
    public static final String KPI_TAUX_UTILISATION = "capacity.taux_utilisation";

    /** KPI key for the team's aggregated net capacity, in person-days. */
    public static final String KPI_CAPACITE_NETTE = "capacity.capacite_nette";

    /** KPI key for the team's average delivered velocity, in points. */
    public static final String KPI_VELOCITE_MOYENNE = "capacity.velocite_moyenne";

    /** KPI key for the team's absence rate, in percent. */
    public static final String KPI_TAUX_ABSENCE = "capacity.taux_absence";

    /** KPI key for the team's count of over-committed events. */
    public static final String KPI_DEPASSEMENTS = "capacity.depassements";

    private final CapacityEventRepository eventRepository;
    private final CapacityEventMemberRepository eventMemberRepository;
    private final CapacityAbsenceRepository absenceRepository;
    private final CapacityVelocityRepository velocityRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param eventRepository       capacity event persistence
     * @param eventMemberRepository capacity event member persistence
     * @param absenceRepository     capacity absence persistence
     * @param velocityRepository    capacity velocity snapshot persistence
     * @param teamMemberRepository  {@code pivot-core-starter}'s team membership persistence
     */
    public KpiService(
            final CapacityEventRepository eventRepository,
            final CapacityEventMemberRepository eventMemberRepository,
            final CapacityAbsenceRepository absenceRepository,
            final CapacityVelocityRepository velocityRepository,
            final TeamMemberRepository teamMemberRepository) {
        this.eventRepository = eventRepository;
        this.eventMemberRepository = eventMemberRepository;
        this.absenceRepository = absenceRepository;
        this.velocityRepository = velocityRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    /**
     * Computes the five E11 KPIs for the team owning {@code eventId}, aggregated across every
     * capacity event of that team/tenant.
     *
     * @param eventId  any capacity event id belonging to the team to report on, from the request
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the team's KPIs
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to a different
     *                                         tenant, or the caller is not a member of its team —
     *                                         all collapse to 404
     */
    public KpiResponse getTeamKpis(final UUID eventId, final Long callerId, final Long tenantId) {
        CapacityEvent anchor = eventRepository.findByIdAndTenantId(eventId, tenantId)
                .orElseThrow(() -> new CapacityEventNotFoundException(eventId));

        teamMemberRepository.findByTeamIdAndUserId(anchor.getTeamId(), callerId)
                .orElseThrow(() -> new CapacityEventNotFoundException(eventId));

        List<CapacityEvent> teamEvents = eventRepository.findByTeamIdAndTenantId(anchor.getTeamId(), tenantId);

        Aggregate aggregate = new Aggregate();
        List<UUID> sprintIds = new ArrayList<>();
        for (CapacityEvent event : teamEvents) {
            List<CapacityEventMember> members = eventMemberRepository.findByEventIdOrderByPositionAsc(event.getId());
            EventCapacityResult capacity = computeCapacity(event, members);
            accumulate(aggregate, event, members, capacity);
            if (event.getType() == CapacityEventType.SPRINT) {
                sprintIds.add(event.getId());
            }
        }

        Double velociteMoyenne = averageDeliveredVelocity(sprintIds);

        Map<String, Double> kpis = new HashMap<>();
        kpis.put(KPI_TAUX_UTILISATION, aggregate.engagementRecommande > 0
                ? CapacityCalculator.round2(100.0 * aggregate.committedPoints / aggregate.engagementRecommande)
                : 0.0);
        kpis.put(KPI_CAPACITE_NETTE, CapacityCalculator.round2(aggregate.netCapacity));
        kpis.put(KPI_VELOCITE_MOYENNE, velociteMoyenne);
        kpis.put(KPI_TAUX_ABSENCE, aggregate.possibleMemberDays > 0
                ? CapacityCalculator.round2(100.0 * aggregate.absentDays / aggregate.possibleMemberDays)
                : 0.0);
        kpis.put(KPI_DEPASSEMENTS, (double) aggregate.overCommittedEventCount);

        return new KpiResponse(anchor.getTeamId(), teamEvents.size(), sprintIds.size(), kpis);
    }

    /**
     * Folds one event's computed capacity into the running team-level aggregate.
     *
     * @param aggregate the running totals, mutated in place
     * @param event     the event just computed
     * @param members   the event's members, in display order — used only to resolve which {@code
     *                  capacity}-returned member results are non-excluded (headcount/absence
     *                  scope)
     * @param capacity  the event's computed capacity
     */
    private static void accumulate(
            final Aggregate aggregate,
            final CapacityEvent event,
            final List<CapacityEventMember> members,
            final EventCapacityResult capacity) {
        aggregate.netCapacity += capacity.totalCapaciteNette();
        aggregate.engagementRecommande += capacity.totalEngagementRecommande();

        Double committedPoints = event.getCommittedPoints();
        if (committedPoints != null) {
            aggregate.committedPoints += committedPoints;
            if (capacity.totalEngagementRecommande() > 0
                    && committedPoints > OVER_COMMIT_THRESHOLD * capacity.totalEngagementRecommande()) {
                aggregate.overCommittedEventCount++;
            }
        }

        Map<String, Boolean> excludedByMemberId = new HashMap<>();
        for (CapacityEventMember member : members) {
            excludedByMemberId.put(member.getId().toString(), member.isExcluded());
        }
        int nonExcludedCount = 0;
        for (MemberCapacityResult result : capacity.members()) {
            Boolean excluded = excludedByMemberId.get(result.memberId());
            if (excluded != null && !excluded) {
                aggregate.absentDays += result.absentWorkingDays();
                nonExcludedCount++;
            }
        }
        aggregate.possibleMemberDays += (double) capacity.totalWorkingDays() * nonExcludedCount;
    }

    /**
     * Averages {@link CapacityVelocity#getPointsLivres()} across every closed-out sprint of the
     * given ids.
     *
     * @param sprintIds the team's sprint-type event ids (closed out or not)
     * @return the average delivered points, rounded to 2 decimals, or {@code null} if no sprint
     *     has been closed out yet (empty {@code sprintIds}, or none has a velocity snapshot)
     */
    private Double averageDeliveredVelocity(final List<UUID> sprintIds) {
        if (sprintIds.isEmpty()) {
            return null;
        }
        List<CapacityVelocity> snapshots = velocityRepository.findBySprintEventIdIn(sprintIds);
        if (snapshots.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (CapacityVelocity snapshot : snapshots) {
            sum += snapshot.getPointsLivres();
        }
        return CapacityCalculator.round2(sum / snapshots.size());
    }

    /**
     * Loads an event's members' absences and drives {@code CapacityCalculator#computeEventCapacity}
     * for it — same construction as {@code CapacitySummaryService#computeCapacity}, duplicated
     * here since that method is private to a file this ticket must not edit.
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

    /** Mutable running totals folded across every event of the team, one field per KPI input. */
    private static final class Aggregate {
        private double netCapacity;
        private double engagementRecommande;
        private double committedPoints;
        private double absentDays;
        private double possibleMemberDays;
        private int overCommittedEventCount;
    }
}
