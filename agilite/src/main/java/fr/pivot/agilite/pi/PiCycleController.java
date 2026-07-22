package fr.pivot.agilite.pi;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.pi.dto.CreateCycleRequest;
import fr.pivot.agilite.pi.dto.CreateTeamRequest;
import fr.pivot.agilite.pi.dto.CycleResponse;
import fr.pivot.agilite.pi.dto.CycleSummaryResponse;
import fr.pivot.agilite.pi.dto.ImportTeamsRequest;
import fr.pivot.agilite.pi.dto.ImportTeamsResponse;
import fr.pivot.agilite.pi.dto.PiCycleTeamResponse;
import fr.pivot.agilite.pi.dto.UpdateCycleRequest;
import fr.pivot.agilite.pi.dto.UpdateIterationRequest;
import fr.pivot.agilite.pi.dto.UpdateTeamRequest;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing PI cycle, iteration, and Train team operations under {@code
 * /pi/cycles} (US50.1.1).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}. Missing,
 * malformed, or rejected tokens result in HTTP 401.
 *
 * <p>The full path (including the application context) is {@code /api/agilite/pi/cycles}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/pi/cycles")
@Validated
public class PiCycleController {

    private final PiCycleService cycleService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param cycleService the PI cycle business logic service (US50.1.1)
     */
    public PiCycleController(final PiCycleService cycleService) {
        this.cycleService = cycleService;
    }

    /**
     * Creates a new PI cycle with its auto-generated iterations.
     *
     * @param request   the creation request
     * @param principal the resolved caller identity
     * @return the created cycle with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CycleResponse create(
            @RequestBody @Valid final CreateCycleRequest request, final RequestPrincipal principal) {
        return cycleService.create(request, principal.userId(), principal.tenantId());
    }

    /**
     * Lists the cycles accessible to the caller.
     *
     * @param principal the resolved caller identity
     * @return the accessible cycles, most recent {@code startDate} first
     */
    @GetMapping
    public List<CycleSummaryResponse> list(final RequestPrincipal principal) {
        return cycleService.list(principal.userId(), principal.tenantId());
    }

    /**
     * Returns a single cycle by its identifier, if the caller has access.
     *
     * @param cycleId   the cycle UUID from the path
     * @param principal the resolved caller identity
     * @return the cycle, or HTTP 404 if not found or inaccessible
     */
    @GetMapping("/{cycleId}")
    public CycleResponse findById(@PathVariable final UUID cycleId, final RequestPrincipal principal) {
        return cycleService.findById(cycleId, principal.userId(), principal.tenantId());
    }

    /**
     * Updates a cycle's own fields.
     *
     * @param cycleId   the cycle UUID from the path
     * @param request   the update request
     * @param principal the resolved caller identity
     * @return the updated cycle response
     */
    @PatchMapping("/{cycleId}")
    public CycleResponse update(
            @PathVariable final UUID cycleId,
            @RequestBody @Valid final UpdateCycleRequest request,
            final RequestPrincipal principal) {
        return cycleService.update(cycleId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Permanently deletes a cycle and all its iterations, Train teams, tickets, and dependencies.
     *
     * @param cycleId   the cycle UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{cycleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID cycleId, final RequestPrincipal principal) {
        cycleService.delete(cycleId, principal.userId(), principal.tenantId());
    }

    /**
     * Manually adds a Train team to a cycle (free-text entry, no source team).
     *
     * @param cycleId   the cycle UUID from the path
     * @param request   the creation request
     * @param principal the resolved caller identity
     * @return the created Train team with HTTP 201 Created
     */
    @PostMapping("/{cycleId}/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public PiCycleTeamResponse addManualTeam(
            @PathVariable final UUID cycleId,
            @RequestBody @Valid final CreateTeamRequest request,
            final RequestPrincipal principal) {
        return cycleService.addManualTeam(cycleId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Imports one or more PIVOT teams as Train team snapshots.
     *
     * @param cycleId   the cycle UUID from the path
     * @param request   the import request
     * @param principal the resolved caller identity
     * @return the import result with HTTP 201 Created
     */
    @PostMapping("/{cycleId}/teams/import")
    @ResponseStatus(HttpStatus.CREATED)
    public ImportTeamsResponse importTeams(
            @PathVariable final UUID cycleId,
            @RequestBody @Valid final ImportTeamsRequest request,
            final RequestPrincipal principal) {
        return cycleService.importTeams(cycleId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Updates a Train team's own fields.
     *
     * @param cycleId   the cycle UUID from the path
     * @param teamId    the Train team UUID from the path
     * @param request   the update request
     * @param principal the resolved caller identity
     * @return the updated Train team response
     */
    @PatchMapping("/{cycleId}/teams/{teamId}")
    public PiCycleTeamResponse updateTeam(
            @PathVariable final UUID cycleId,
            @PathVariable final UUID teamId,
            @RequestBody @Valid final UpdateTeamRequest request,
            final RequestPrincipal principal) {
        return cycleService.updateTeam(cycleId, teamId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Deletes a Train team from a cycle — its tickets fall back to the Train row, never deleted.
     *
     * @param cycleId   the cycle UUID from the path
     * @param teamId    the Train team UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{cycleId}/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTeam(
            @PathVariable final UUID cycleId, @PathVariable final UUID teamId, final RequestPrincipal principal) {
        cycleService.deleteTeam(cycleId, teamId, principal.userId(), principal.tenantId());
    }

    /**
     * Adjusts an already-generated iteration.
     *
     * @param cycleId     the cycle UUID from the path
     * @param iterationId the iteration UUID from the path
     * @param request     the update request
     * @param principal   the resolved caller identity
     * @return the updated cycle response
     */
    @PatchMapping("/{cycleId}/iterations/{iterationId}")
    public CycleResponse updateIteration(
            @PathVariable final UUID cycleId,
            @PathVariable final UUID iterationId,
            @RequestBody @Valid final UpdateIterationRequest request,
            final RequestPrincipal principal) {
        return cycleService.updateIteration(cycleId, iterationId, request, principal.userId(), principal.tenantId());
    }
}
