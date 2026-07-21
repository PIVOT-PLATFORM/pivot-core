package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.calc.CapacityCalculator;
import fr.pivot.agilite.capacity.dto.CapacityBurndownResponse;
import fr.pivot.agilite.capacity.dto.CapacityBurndownResponse.BurndownPoint;
import fr.pivot.agilite.capacity.dto.CapacityHistoryResponse;
import fr.pivot.agilite.capacity.dto.CapacityHistoryResponse.HistoryPoint;
import fr.pivot.agilite.capacity.dto.CapacityVelocityRequest;
import fr.pivot.agilite.capacity.dto.CapacityVelocityResponse;
import fr.pivot.agilite.capacity.dto.VelocityForecast;
import fr.pivot.agilite.capacity.exception.CapacityAccessDeniedException;
import fr.pivot.agilite.capacity.exception.CapacityEventNotFoundException;
import fr.pivot.agilite.capacity.exception.CapacityValidationException;
import fr.pivot.core.team.TeamMember;
import fr.pivot.core.team.TeamMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for the sprint velocity snapshot, rolling velocity/forecast history, and
 * burndown endpoints (F11.4 — E11 capacity planning).
 *
 * <p>Team existence/tenant-ownership is enforced via {@link CapacityEventRepository#findByIdAndTenantId};
 * caller membership of the event's owning team is validated directly against {@code
 * fr.pivot.core.team.TeamMemberRepository}, exported as-is by {@code pivot-core-starter} — same
 * inline-access pattern as {@code fr.pivot.agilite.retro.session.RetroSessionService}.
 */
@Service
@Transactional
public class CapacityVelocityService {

    /** Number of most recent sprints considered by {@link #history}'s rolling forecast (E11 default). */
    static final int HISTORY_WINDOW = 3;

    private final CapacityEventRepository eventRepository;
    private final CapacityVelocityRepository velocityRepository;
    private final CapacityBurndownPointRepository burndownPointRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * Constructs the service with its required dependencies.
     *
     * @param eventRepository          capacity event persistence
     * @param velocityRepository       velocity snapshot persistence
     * @param burndownPointRepository  burndown point persistence
     * @param teamMemberRepository     {@code pivot-core-starter}'s team membership persistence
     */
    public CapacityVelocityService(
            final CapacityEventRepository eventRepository,
            final CapacityVelocityRepository velocityRepository,
            final CapacityBurndownPointRepository burndownPointRepository,
            final TeamMemberRepository teamMemberRepository) {
        this.eventRepository = eventRepository;
        this.velocityRepository = velocityRepository;
        this.burndownPointRepository = burndownPointRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    /**
     * Upserts a sprint's velocity snapshot: creates one if the sprint has never been closed out
     * before, otherwise replaces the existing one — {@link CapacityVelocity} exposes no setters
     * (an intentionally immutable snapshot), so "replace" means deleting the previous row before
     * inserting the new one, inside the same transaction.
     *
     * @param eventId  the sprint event's id, from the path
     * @param request  the validated request body
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the upserted velocity snapshot
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     * @throws CapacityValidationException    if the event is not a {@link CapacityEventType#SPRINT}
     * @throws CapacityAccessDeniedException  if the caller is a {@link TeamMember#ROLE_MEMBRE}
     *                                         (VIEWER) of the event's team
     */
    public CapacityVelocityResponse upsertVelocity(
            final UUID eventId, final CapacityVelocityRequest request, final Long callerId, final Long tenantId) {
        CapacityEvent event = requireSprintEvent(eventId, tenantId);
        TeamMember membership = requireMembership(event, callerId);
        requireWriteRole(membership);

        velocityRepository.findBySprintEventId(eventId).ifPresent(existing -> {
            velocityRepository.delete(existing);
            velocityRepository.flush();
        });

        CapacityVelocity created = new CapacityVelocity(
                eventId, request.pointsEngages(), request.pointsLivres(), Instant.now());
        return CapacityVelocityResponse.from(velocityRepository.save(created));
    }

    /**
     * Returns the team's recent velocity history (last {@link #HISTORY_WINDOW} sprints, oldest
     * first) together with {@code CapacityCalculator}'s rolling forecast built from the sprints
     * that have a velocity snapshot.
     *
     * @param eventId  the sprint event's id, from the path — anchors which team's history is read
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the history + forecast
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     * @throws CapacityValidationException    if the event is not a {@link CapacityEventType#SPRINT}
     */
    @Transactional(readOnly = true)
    public CapacityHistoryResponse history(final UUID eventId, final Long callerId, final Long tenantId) {
        CapacityEvent event = requireSprintEvent(eventId, tenantId);
        requireMembership(event, callerId);

        List<CapacityEvent> recentSprints = eventRepository.findByTeamIdAndTenantId(event.getTeamId(), tenantId)
                .stream()
                .filter(candidate -> candidate.getType() == CapacityEventType.SPRINT)
                .sorted(Comparator.comparing(CapacityEvent::getStartDate))
                .toList();
        int fromIndex = Math.max(0, recentSprints.size() - HISTORY_WINDOW);
        List<CapacityEvent> window = recentSprints.subList(fromIndex, recentSprints.size());

        Map<UUID, CapacityVelocity> velocitiesBySprintId = new HashMap<>();
        for (CapacityVelocity velocity : velocityRepository.findBySprintEventIdIn(
                window.stream().map(CapacityEvent::getId).toList())) {
            velocitiesBySprintId.put(velocity.getSprintEventId(), velocity);
        }

        List<HistoryPoint> history = new ArrayList<>(window.size());
        List<Double> pointsLivresHistory = new ArrayList<>(window.size());
        for (CapacityEvent sprint : window) {
            CapacityVelocity velocity = velocitiesBySprintId.get(sprint.getId());
            Double pointsEngages = velocity != null ? velocity.getPointsEngages() : null;
            Double pointsLivres = velocity != null ? velocity.getPointsLivres() : null;
            history.add(new HistoryPoint(sprint.getId(), sprint.getName(), sprint.getStartDate(), pointsEngages, pointsLivres));
            pointsLivresHistory.add(pointsLivres);
        }

        VelocityForecast forecast = CapacityCalculator.forecastVelocity(pointsLivresHistory, HISTORY_WINDOW);
        return new CapacityHistoryResponse(history, forecast);
    }

    /**
     * Returns a sprint's real burndown line (as recorded in {@code capacity_burndown_point})
     * together with a derived ideal line, linear from the event's committed points on the first
     * day of its window down to {@code 0} on the last.
     *
     * @param eventId  the sprint event's id, from the path
     * @param callerId the authenticated caller's {@code public.users.id}
     * @param tenantId the authenticated caller's {@code public.tenants.id}
     * @return the real + ideal burndown lines
     * @throws CapacityEventNotFoundException if the event does not exist, belongs to another
     *                                         tenant, or the caller is not a member of its team
     * @throws CapacityValidationException    if the event is not a {@link CapacityEventType#SPRINT}
     */
    @Transactional(readOnly = true)
    public CapacityBurndownResponse burndown(final UUID eventId, final Long callerId, final Long tenantId) {
        CapacityEvent event = requireSprintEvent(eventId, tenantId);
        requireMembership(event, callerId);

        List<BurndownPoint> real = burndownPointRepository.findByEventIdOrderByDateAsc(eventId).stream()
                .map(point -> new BurndownPoint(point.getDate(), point.getPointsRestants()))
                .toList();

        return new CapacityBurndownResponse(real, idealLine(event));
    }

    /**
     * Builds the ideal burndown line: one point per calendar day of {@code [event.startDate,
     * event.endDate]}, linearly interpolated from {@code committedPoints} (or {@code 0} if unset)
     * on the first day down to {@code 0} on the last. A single-day event's only point is
     * {@code 0}.
     *
     * @param event the sprint event
     * @return the ideal line, one point per calendar day, oldest first
     */
    private static List<BurndownPoint> idealLine(final CapacityEvent event) {
        double committed = event.getCommittedPoints() != null ? event.getCommittedPoints() : 0;
        LocalDate start = event.getStartDate();
        LocalDate end = event.getEndDate();
        int totalDays = (int) (java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1);

        List<BurndownPoint> ideal = new ArrayList<>(Math.max(totalDays, 1));
        if (totalDays <= 1) {
            ideal.add(new BurndownPoint(start, 0));
            return ideal;
        }
        for (int day = 0; day < totalDays; day++) {
            double fraction = (totalDays - 1 - day) / (double) (totalDays - 1);
            ideal.add(new BurndownPoint(start.plusDays(day), CapacityCalculator.round2(committed * fraction)));
        }
        return ideal;
    }

    /**
     * Resolves {@code eventId} for the caller's tenant and validates it is a {@link
     * CapacityEventType#SPRINT} — every F11.4 endpoint is anchored to a specific sprint.
     *
     * @param eventId  the event id, from the path
     * @param tenantId the caller's tenant id
     * @return the resolved sprint event
     * @throws CapacityEventNotFoundException if not found or belonging to another tenant
     * @throws CapacityValidationException    if the event is not a {@code SPRINT}
     */
    private CapacityEvent requireSprintEvent(final UUID eventId, final Long tenantId) {
        CapacityEvent event = eventRepository.findByIdAndTenantId(eventId, tenantId)
                .orElseThrow(() -> new CapacityEventNotFoundException(eventId));
        if (event.getType() != CapacityEventType.SPRINT) {
            throw new CapacityValidationException("NOT_A_SPRINT", "Capacity event is not a sprint: " + eventId);
        }
        return event;
    }

    /**
     * Resolves the caller's membership of {@code event}'s owning team, collapsing "not a member"
     * into the same 404 as "event not found" (never confirms the event's existence to a
     * non-member of its team).
     *
     * @param event    the resolved event
     * @param callerId the authenticated caller's {@code public.users.id}
     * @return the caller's team membership row
     * @throws CapacityEventNotFoundException if the caller is not a member of {@code event}'s team
     */
    private TeamMember requireMembership(final CapacityEvent event, final Long callerId) {
        return teamMemberRepository.findByTeamIdAndUserId(event.getTeamId(), callerId)
                .orElseThrow(() -> new CapacityEventNotFoundException(event.getId()));
    }

    /**
     * Enforces the OWNER/EDITOR write requirement: any role other than {@link
     * TeamMember#ROLE_MEMBRE} (VIEWER) may write.
     *
     * @param membership the caller's resolved team membership
     * @throws CapacityAccessDeniedException if the caller's role is {@link TeamMember#ROLE_MEMBRE}
     */
    private static void requireWriteRole(final TeamMember membership) {
        if (TeamMember.ROLE_MEMBRE.equals(membership.getRole())) {
            throw new CapacityAccessDeniedException(
                    "Capacity velocity write requires OWNER/EDITOR role, caller is VIEWER");
        }
    }
}
