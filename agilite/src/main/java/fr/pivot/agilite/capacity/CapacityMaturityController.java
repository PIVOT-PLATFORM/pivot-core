package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.MaturityResponse;
import fr.pivot.agilite.capacity.dto.UpdateMaturityRequest;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing a team's agile-maturity tier under {@code
 * /capacity/teams/{teamId}/capacity-maturity} (US11.6.4).
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/capacity/teams/{teamId}/capacity-maturity}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity/teams/{teamId}/capacity-maturity")
@Validated
public class CapacityMaturityController {

    private final CapacityTeamMaturityService maturityService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param maturityService the team-maturity business logic service (US11.6.4)
     */
    public CapacityMaturityController(final CapacityTeamMaturityService maturityService) {
        this.maturityService = maturityService;
    }

    /**
     * Returns a team's effective maturity and derived focus-factor/margin defaults.
     *
     * @param teamId    the team's {@code public.teams.id} from the path
     * @param principal the resolved caller identity
     * @return the effective maturity response
     */
    @GetMapping
    public MaturityResponse get(@PathVariable final Long teamId, final RequestPrincipal principal) {
        return maturityService.get(teamId, principal.userId(), principal.tenantId());
    }

    /**
     * Updates a team's maturity tier, recording the change in its history.
     *
     * @param teamId    the team's {@code public.teams.id} from the path
     * @param request   the update request
     * @param principal the resolved caller identity
     * @return the updated effective maturity response
     */
    @PatchMapping
    public MaturityResponse update(
            @PathVariable final Long teamId,
            @RequestBody @Valid final UpdateMaturityRequest request,
            final RequestPrincipal principal) {
        return maturityService.update(teamId, request, principal.userId(), principal.tenantId());
    }
}
