package fr.pivot.agilite.capacity;

import fr.pivot.agilite.exception.CapacityNotFoundException;
import fr.pivot.agilite.team.TeamMembershipService;
import fr.pivot.agilite.team.dto.TeamResponse;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared capacity event access resolution (US11.1.1).
 *
 * <p>An event is accessible to its creator, or to any caller who is currently a member of the
 * event's owning team ({@link CapacityEvent#getTeamId()}) — same "any team member manages, no
 * dedicated RTE/Scrum-Master platform role" convention already applied to Wheel/Retro/Standup/PI
 * Planning this session. 404 (never 403) on any access failure — same anti-enumeration
 * convention as {@code WheelService#resolveAccessibleWheel}/{@code
 * fr.pivot.agilite.pi.PiCycleAccessService}.
 */
@Service
public class CapacityEventAccessService {

    private final CapacityEventRepository eventRepository;
    private final TeamMembershipService teamMembershipService;

    /**
     * Creates the service with its required dependencies.
     *
     * @param eventRepository       repository for event persistence
     * @param teamMembershipService shared team-resolution/membership-check helper
     */
    public CapacityEventAccessService(
            final CapacityEventRepository eventRepository, final TeamMembershipService teamMembershipService) {
        this.eventRepository = eventRepository;
        this.teamMembershipService = teamMembershipService;
    }

    /**
     * Resolves an event by id and tenant, then verifies the caller may access it.
     *
     * @param eventId      the event UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the resolved event
     * @throws CapacityNotFoundException if the event does not exist, belongs to another tenant,
     *     or the caller is neither its creator nor a member of its owning team
     */
    public CapacityEvent resolveEventForCaller(final UUID eventId, final Long callerUserId, final Long tenantId) {
        CapacityEvent event = eventRepository.findByIdAndTenantId(eventId, tenantId)
                .orElseThrow(() -> new CapacityNotFoundException("capacity event", eventId));
        if (!isAccessible(event, callerUserId, tenantId)) {
            throw new CapacityNotFoundException("capacity event", eventId);
        }
        return event;
    }

    /**
     * Checks whether a caller may access a given event, without throwing.
     *
     * @param event        the event, already resolved for the caller's tenant
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return {@code true} if the caller created the event, or is a member of its owning team
     */
    public boolean isAccessible(final CapacityEvent event, final Long callerUserId, final Long tenantId) {
        if (event.getCreatedBy().equals(callerUserId)) {
            return true;
        }
        Set<Long> callerTeamIds = teamMembershipService.listMyTeams(callerUserId, tenantId).stream()
                .map(TeamResponse::id)
                .collect(Collectors.toSet());
        return callerTeamIds.contains(event.getTeamId());
    }
}
