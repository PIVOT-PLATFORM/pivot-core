package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.EventResponse;
import fr.pivot.agilite.capacity.dto.UpdateVelocityRequest;
import fr.pivot.agilite.capacity.dto.VelocityAverageResponse;
import fr.pivot.agilite.capacity.dto.VelocityHistoryEntryResponse;
import fr.pivot.agilite.exception.CapacityValidationException;
import fr.pivot.agilite.team.TeamMembershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Business logic for a {@code SPRINT} event's velocity entry, history, and moving-average
 * suggestion (US11.4.1).
 *
 * <p><strong>No Scrum Poker (E09) integration at the socle</strong> (US11.4.1 §Architecture) —
 * {@code committedPoints} is entered manually here, no call into {@code fr.pivot.agilite.poker}.
 */
@Service
@Transactional
public class CapacityVelocityService {

    private static final int DEFAULT_HISTORY_LIMIT = 10;
    private static final int DEFAULT_AVERAGE_COUNT = 3;
    private static final double DEFAULT_AVERAGE_FACTOR = 0.85;
    private static final int MAX_QUERY_PARAM = 50;
    private static final double MAX_FACTOR = 2.0;

    private final CapacityEventService eventService;
    private final CapacityEventRepository eventRepository;
    private final TeamMembershipService teamMembershipService;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventService          shared event access resolution
     * @param eventRepository       repository for event persistence
     * @param teamMembershipService shared team-resolution/membership-check helper
     */
    public CapacityVelocityService(
            final CapacityEventService eventService,
            final CapacityEventRepository eventRepository,
            final TeamMembershipService teamMembershipService) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.teamMembershipService = teamMembershipService;
    }

    /**
     * Records committed/completed points on a {@code SPRINT} event — both independently
     * optional.
     *
     * @param eventId      the event UUID
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated event's response
     */
    public EventResponse updateVelocity(
            final UUID eventId, final UpdateVelocityRequest request, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = eventService.resolveForCaller(eventId, callerUserId, tenantId);
        if (event.getType() != CapacityEventType.SPRINT) {
            throw new CapacityValidationException(
                    "INVALID_EVENT_TYPE_FOR_VELOCITY", "Velocity can only be recorded on SPRINT events");
        }
        if (request.committedPoints() != null) {
            requireNonNegative(request.committedPoints());
            event.setCommittedPoints(request.committedPoints());
        }
        if (request.completedPoints() != null) {
            requireNonNegative(request.completedPoints());
            event.setCompletedPoints(request.completedPoints());
        }
        return eventService.findById(eventId, callerUserId, tenantId);
    }

    /**
     * Lists a team's most recent completed sprints, most recent {@code endDate} first.
     *
     * @param teamId       the team's {@code public.teams.id}
     * @param limit        max number of entries, default {@value #DEFAULT_HISTORY_LIMIT}
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the team's velocity history
     */
    @Transactional(readOnly = true)
    public List<VelocityHistoryEntryResponse> history(
            final Long teamId, final Integer limit, final Long callerUserId, final Long tenantId) {
        teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
        int effectiveLimit = limit != null ? limit : DEFAULT_HISTORY_LIMIT;
        validateQueryParam(effectiveLimit);
        return completedSprints(teamId).stream()
                .limit(effectiveLimit)
                .map(event -> new VelocityHistoryEntryResponse(
                        event.getId(), event.getName(), event.getEndDate(), event.getCommittedPoints(), event.getCompletedPoints()))
                .toList();
    }

    /**
     * Computes a team's simple moving average velocity and suggested next-sprint capacity.
     *
     * @param teamId       the team's {@code public.teams.id}
     * @param count        number of most-recent completed sprints to average, default {@value
     *                     #DEFAULT_AVERAGE_COUNT}
     * @param factor       multiplier applied to the average, default {@value
     *                     #DEFAULT_AVERAGE_FACTOR}
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the average and suggested capacity, both {@code null} if no completed sprint exists
     */
    @Transactional(readOnly = true)
    public VelocityAverageResponse average(
            final Long teamId, final Integer count, final Double factor, final Long callerUserId, final Long tenantId) {
        teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
        int effectiveCount = count != null ? count : DEFAULT_AVERAGE_COUNT;
        double effectiveFactor = factor != null ? factor : DEFAULT_AVERAGE_FACTOR;
        validateQueryParam(effectiveCount);
        if (effectiveFactor < 0 || effectiveFactor > MAX_FACTOR) {
            throw new CapacityValidationException("INVALID_QUERY_PARAM", "factor must be between 0 and " + MAX_FACTOR);
        }

        List<CapacityEvent> lastCompleted = completedSprints(teamId).stream().limit(effectiveCount).toList();
        if (lastCompleted.isEmpty()) {
            return new VelocityAverageResponse(null, null);
        }
        double average = lastCompleted.stream().mapToInt(CapacityEvent::getCompletedPoints).average().orElseThrow();
        return new VelocityAverageResponse(average, average * effectiveFactor);
    }

    /**
     * Fetches a team's {@code SPRINT} events with {@code completedPoints} set, most recent
     * {@code endDate} first — the shared source for both {@link #history} and {@link #average}.
     *
     * @param teamId the team's {@code public.teams.id}
     * @return the team's completed sprints
     */
    private List<CapacityEvent> completedSprints(final Long teamId) {
        return eventRepository.findAllByTeamIdAndTypeAndCompletedPointsIsNotNullOrderByEndDateDesc(
                teamId, CapacityEventType.SPRINT);
    }

    private void requireNonNegative(final int points) {
        if (points < 0) {
            throw new CapacityValidationException("INVALID_POINTS", "Points must not be negative");
        }
    }

    private void validateQueryParam(final int value) {
        if (value < 1 || value > MAX_QUERY_PARAM) {
            throw new CapacityValidationException("INVALID_QUERY_PARAM", "Must be between 1 and " + MAX_QUERY_PARAM);
        }
    }
}
