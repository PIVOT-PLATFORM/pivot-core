package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CapacityBurndownResponse;
import fr.pivot.agilite.capacity.dto.CapacityHistoryResponse;
import fr.pivot.agilite.capacity.dto.CapacityVelocityRequest;
import fr.pivot.agilite.capacity.dto.CapacityVelocityResponse;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing sprint velocity snapshot, rolling velocity/forecast history, and
 * burndown operations under {@code /capacity/events/{id}} (F11.4 — E11 capacity planning).
 *
 * <p>Full path (including the application context) is {@code /api/agilite/capacity/events/{id}}.
 * Every operation requires a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@code RequestPrincipalResolver} (EN08.3).
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity")
@Validated
public class CapacityVelocityController {

    private final CapacityVelocityService velocityService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param velocityService the F11.4 business logic service
     */
    public CapacityVelocityController(final CapacityVelocityService velocityService) {
        this.velocityService = velocityService;
    }

    /**
     * Upserts a sprint's velocity snapshot (committed/completed points).
     *
     * @param id        the sprint event's id, from the path
     * @param request   the validated request body
     * @param principal the resolved caller identity
     * @return the upserted velocity snapshot
     */
    @PatchMapping("/events/{id}/velocity")
    public CapacityVelocityResponse upsertVelocity(
            @PathVariable final UUID id,
            @RequestBody @Valid final CapacityVelocityRequest request,
            final RequestPrincipal principal) {
        return velocityService.upsertVelocity(id, request, principal.userId(), principal.tenantId());
    }

    /**
     * Returns the sprint's team's recent velocity history and rolling forecast.
     *
     * @param id        the sprint event's id, from the path
     * @param principal the resolved caller identity
     * @return the velocity history and forecast
     */
    @GetMapping("/events/{id}/history")
    public CapacityHistoryResponse history(
            @PathVariable final UUID id,
            final RequestPrincipal principal) {
        return velocityService.history(id, principal.userId(), principal.tenantId());
    }

    /**
     * Returns the sprint's real burndown line plus a derived ideal line.
     *
     * @param id        the sprint event's id, from the path
     * @param principal the resolved caller identity
     * @return the real and ideal burndown lines
     */
    @GetMapping("/events/{id}/burndown")
    public CapacityBurndownResponse burndown(
            @PathVariable final UUID id,
            final RequestPrincipal principal) {
        return velocityService.burndown(id, principal.userId(), principal.tenantId());
    }
}
