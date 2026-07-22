package fr.pivot.agilite.pi;

import fr.pivot.agilite.exception.PiNotFoundException;
import fr.pivot.agilite.team.TeamMembershipService;
import fr.pivot.agilite.team.dto.TeamResponse;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared PI cycle access resolution (US50.1.1 §Architecture — modèle d'accès Train).
 *
 * <p>A cycle is accessible to its creator, or to any caller who is a member of at least one
 * {@code public.teams} row currently imported as one of the cycle's Train teams ({@link
 * PiCycleTeam#getSourceTeamId()}). Manually-added Train teams (no {@code sourceTeamId}) grant no
 * membership-based access to anyone but the creator. 404 (never 403) on any access failure —
 * same anti-enumeration convention as {@code WheelService#resolveAccessibleWheel}.
 */
@Service
public class PiCycleAccessService {

    private final PiCycleRepository cycleRepository;
    private final TeamMembershipService teamMembershipService;

    /**
     * Creates the service with its required dependencies.
     *
     * @param cycleRepository       repository for cycle persistence
     * @param teamMembershipService shared team-resolution/membership-check helper
     */
    public PiCycleAccessService(
            final PiCycleRepository cycleRepository, final TeamMembershipService teamMembershipService) {
        this.cycleRepository = cycleRepository;
        this.teamMembershipService = teamMembershipService;
    }

    /**
     * Resolves a cycle by id and tenant, then verifies the caller may access it.
     *
     * @param cycleId      the cycle UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the resolved cycle
     * @throws PiNotFoundException if the cycle does not exist, belongs to another tenant, or the
     *     caller is neither its creator nor a member of one of its imported Train teams
     */
    public PiCycle resolveCycleForCaller(final UUID cycleId, final Long callerUserId, final Long tenantId) {
        PiCycle cycle = cycleRepository.findByIdAndTenantId(cycleId, tenantId)
                .orElseThrow(() -> new PiNotFoundException("PI cycle", cycleId));
        if (!isAccessible(cycle, callerUserId, tenantId)) {
            throw new PiNotFoundException("PI cycle", cycleId);
        }
        return cycle;
    }

    /**
     * Checks whether a caller may access a given cycle, without throwing.
     *
     * @param cycle        the cycle, already resolved for the caller's tenant
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return {@code true} if the caller created the cycle, or is a member of at least one of its
     *     imported Train teams
     */
    public boolean isAccessible(final PiCycle cycle, final Long callerUserId, final Long tenantId) {
        if (cycle.getCreatedBy().equals(callerUserId)) {
            return true;
        }
        Set<Long> callerTeamIds = teamMembershipService.listMyTeams(callerUserId, tenantId).stream()
                .map(TeamResponse::id)
                .collect(Collectors.toSet());
        return cycle.getTeams().stream()
                .map(PiCycleTeam::getSourceTeamId)
                .filter(sourceTeamId -> sourceTeamId != null)
                .anyMatch(callerTeamIds::contains);
    }
}
