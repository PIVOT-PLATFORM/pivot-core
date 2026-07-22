package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.MaturityResponse;
import fr.pivot.agilite.capacity.dto.UpdateMaturityRequest;
import fr.pivot.agilite.team.TeamMembershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for a team's agile-maturity tier and its derived focus-factor/margin defaults
 * (US11.6.4).
 */
@Service
@Transactional
public class CapacityTeamMaturityService {

    private static final String SOURCE_TEAM_MATURITY = "TEAM_MATURITY";
    private static final String SOURCE_DEFAULT = "DEFAULT";

    private final CapacityTeamMaturityRepository maturityRepository;
    private final CapacityTeamMaturityHistoryRepository historyRepository;
    private final TeamMembershipService teamMembershipService;

    /**
     * Creates the service with its required dependencies.
     *
     * @param maturityRepository     repository for the current-maturity row per team
     * @param historyRepository      repository for the append-only change history
     * @param teamMembershipService  shared team-resolution/membership-check helper
     */
    public CapacityTeamMaturityService(
            final CapacityTeamMaturityRepository maturityRepository,
            final CapacityTeamMaturityHistoryRepository historyRepository,
            final TeamMembershipService teamMembershipService) {
        this.maturityRepository = maturityRepository;
        this.historyRepository = historyRepository;
        this.teamMembershipService = teamMembershipService;
    }

    /**
     * Updates (or sets for the first time) a team's maturity tier, appending a history entry.
     *
     * @param teamId       the team's {@code public.teams.id}
     * @param request      the update request
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the updated effective maturity response
     */
    public MaturityResponse update(
            final Long teamId, final UpdateMaturityRequest request, final Long callerUserId, final Long tenantId) {
        teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
        CapacityMaturityLevel previous = maturityRepository.findByTeamIdAndTenantId(teamId, tenantId)
                .map(CapacityTeamMaturity::getMaturity)
                .orElse(null);

        CapacityTeamMaturity row = maturityRepository.findByTeamIdAndTenantId(teamId, tenantId)
                .orElseGet(() -> new CapacityTeamMaturity(tenantId, teamId, request.maturity(), callerUserId));
        row.setMaturity(request.maturity());
        row.setUpdatedBy(callerUserId);
        maturityRepository.save(row);

        historyRepository.save(
                new CapacityTeamMaturityHistory(tenantId, teamId, previous, request.maturity(), callerUserId));

        return toResponse(request.maturity());
    }

    /**
     * Returns a team's effective maturity and derived defaults.
     *
     * @param teamId       the team's {@code public.teams.id}
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the effective maturity response
     */
    @Transactional(readOnly = true)
    public MaturityResponse get(final Long teamId, final Long callerUserId, final Long tenantId) {
        teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
        CapacityMaturityLevel maturity = maturityRepository.findByTeamIdAndTenantId(teamId, tenantId)
                .map(CapacityTeamMaturity::getMaturity)
                .orElse(null);
        return toResponse(maturity);
    }

    /**
     * Resolves a team's configured maturity tier, or {@code null} if unconfigured — internal
     * helper for {@link CapacitySummaryService}, avoids a second access-check round trip.
     *
     * @param teamId   the team's {@code public.teams.id}
     * @param tenantId the calling tenant's {@code public.tenants.id}
     * @return the team's maturity, or {@code null}
     */
    CapacityMaturityLevel resolveMaturity(final Long teamId, final Long tenantId) {
        return maturityRepository.findByTeamIdAndTenantId(teamId, tenantId)
                .map(CapacityTeamMaturity::getMaturity)
                .orElse(null);
    }

    private MaturityResponse toResponse(final CapacityMaturityLevel maturity) {
        CapacityMaturityDefaults.Defaults defaults = CapacityMaturityDefaults.forMaturity(maturity);
        String source = maturity != null ? SOURCE_TEAM_MATURITY : SOURCE_DEFAULT;
        return new MaturityResponse(maturity, defaults.focusFactorPercent(), defaults.marginPercent(), source);
    }
}
