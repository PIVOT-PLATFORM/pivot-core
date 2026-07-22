package fr.pivot.agilite.pi;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.pi.dto.CreateDependencyRequest;
import fr.pivot.agilite.pi.dto.DependencyResponse;
import fr.pivot.agilite.pi.dto.UpdateDependencyRequest;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing Program Board dependency operations under {@code
 * /pi/cycles/{cycleId}/dependencies} (US50.3.2).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}.
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/pi/cycles/{cycleId}/dependencies}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/pi/cycles")
@Validated
public class PiDependencyController {

    private final PiDependencyService dependencyService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param dependencyService the Program Board dependency business logic service (US50.3.2)
     */
    public PiDependencyController(final PiDependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    /**
     * Creates a new dependency between two tickets of the same cycle.
     *
     * @param cycleId   the cycle UUID from the path
     * @param request   the creation request
     * @param principal the resolved caller identity
     * @return the created dependency with HTTP 201 Created
     */
    @PostMapping("/{cycleId}/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    public DependencyResponse create(
            @PathVariable final UUID cycleId,
            @RequestBody @Valid final CreateDependencyRequest request,
            final RequestPrincipal principal) {
        return dependencyService.create(cycleId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Updates a dependency's status/note.
     *
     * @param cycleId      the cycle UUID from the path
     * @param dependencyId the dependency UUID from the path
     * @param request      the update request
     * @param principal    the resolved caller identity
     * @return the updated dependency response
     */
    @PatchMapping("/{cycleId}/dependencies/{dependencyId}")
    public DependencyResponse update(
            @PathVariable final UUID cycleId,
            @PathVariable final UUID dependencyId,
            @RequestBody @Valid final UpdateDependencyRequest request,
            final RequestPrincipal principal) {
        return dependencyService.update(cycleId, dependencyId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Deletes a dependency.
     *
     * @param cycleId      the cycle UUID from the path
     * @param dependencyId the dependency UUID from the path
     * @param principal    the resolved caller identity
     */
    @DeleteMapping("/{cycleId}/dependencies/{dependencyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable final UUID cycleId,
            @PathVariable final UUID dependencyId,
            final RequestPrincipal principal) {
        dependencyService.delete(cycleId, dependencyId, principal.userId(), principal.tenantId());
    }
}
