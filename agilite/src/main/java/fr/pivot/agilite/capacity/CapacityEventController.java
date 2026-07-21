package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.cadence.CadenceRequest;
import fr.pivot.agilite.capacity.cadence.CadenceSprintResponse;
import fr.pivot.agilite.capacity.dto.CapacityEventChildResponse;
import fr.pivot.agilite.capacity.dto.CapacityEventRequest;
import fr.pivot.agilite.capacity.dto.CapacityEventResponse;
import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing capacity event CRUD and PI/sprint hierarchy operations under {@code
 * /capacity/events} (E11 — F11.1 events CRUD + F11.3 hierarchy).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}. Missing,
 * malformed, or rejected tokens result in HTTP 401.
 *
 * <p>The full path (including the application context) is {@code /api/agilite/capacity/events}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/capacity/events")
@Validated
public class CapacityEventController {

    private final CapacityEventService eventService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param eventService the capacity event business logic service
     */
    public CapacityEventController(final CapacityEventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Creates a new capacity event.
     *
     * @param request   the creation request — teamId, type, name, startDate/endDate, and
     *                  optional parentId/maturityLevel/focusFactor/margeSecurite/pointsPerDay/
     *                  committedPoints/workingDays/notes/status
     * @param principal the resolved caller identity (user + tenant)
     * @return the created event with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CapacityEventResponse create(
            @RequestBody @Valid final CapacityEventRequest request,
            final RequestPrincipal principal) {
        return eventService.create(request, principal.userId(), principal.tenantId());
    }

    /**
     * Lists the capacity events of every team the caller belongs to, optionally filtered.
     *
     * @param teamId    restricts the listing to a single team, or {@code null} for all of the
     *                  caller's teams
     * @param type      restricts the listing to a single event type, or {@code null} for all
     * @param status    restricts the listing to a single lifecycle status, or {@code null} for
     *                  all
     * @param principal the resolved caller identity
     * @return the matching events
     */
    @GetMapping
    public List<CapacityEventResponse> list(
            @RequestParam(required = false) final Long teamId,
            @RequestParam(required = false) final CapacityEventType type,
            @RequestParam(required = false) final CapacityEventStatus status,
            final RequestPrincipal principal) {
        return eventService.list(teamId, type, status, principal.userId(), principal.tenantId());
    }

    /**
     * Returns a single capacity event by its identifier, if the caller has access.
     *
     * @param id        the event UUID from the path
     * @param principal the resolved caller identity
     * @return the event, or HTTP 404 if not found or inaccessible
     */
    @GetMapping("/{id}")
    public CapacityEventResponse findById(
            @PathVariable final UUID id,
            final RequestPrincipal principal) {
        return eventService.findById(id, principal.userId(), principal.tenantId());
    }

    /**
     * Updates a capacity event's mutable fields.
     *
     * @param id        the event UUID from the path
     * @param request   the update request
     * @param principal the resolved caller identity
     * @return the updated event
     */
    @PutMapping("/{id}")
    public CapacityEventResponse update(
            @PathVariable final UUID id,
            @RequestBody @Valid final CapacityEventRequest request,
            final RequestPrincipal principal) {
        return eventService.update(id, request, principal.userId(), principal.tenantId());
    }

    /**
     * Permanently deletes a capacity event.
     *
     * @param id        the event UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable final UUID id,
            final RequestPrincipal principal) {
        eventService.delete(id, principal.userId(), principal.tenantId());
    }

    /**
     * Lists a PI's direct children (e.g. its sprints).
     *
     * @param piId      the parent event's UUID from the path
     * @param principal the resolved caller identity
     * @return the parent's direct children
     */
    @GetMapping("/{piId}/children")
    public List<CapacityEventChildResponse> children(
            @PathVariable final UUID piId,
            final RequestPrincipal principal) {
        return eventService.children(piId, principal.userId(), principal.tenantId());
    }

    /**
     * Auto-generates a PI's child sprints from a cadence spec (F11.5 — PI/SAFe cadence, the
     * "auto" side of the period auto|manual distinction). "Manuel" is simply not calling this
     * endpoint and creating each {@code SPRINT} event individually via {@code POST
     * /capacity/events} (F11.1).
     *
     * @param piId      the parent PI event's UUID from the path
     * @param request   the cadence spec — sprint length (days or weeks), sprint count, and
     *                  whether to append a trailing SAFe Innovation &amp; Planning sprint
     * @param principal the resolved caller identity
     * @return the generated sprints, in chronological order, with HTTP 201 Created
     */
    @PostMapping("/{piId}/cadence")
    @ResponseStatus(HttpStatus.CREATED)
    public List<CadenceSprintResponse> generateCadence(
            @PathVariable final UUID piId,
            @RequestBody @Valid final CadenceRequest request,
            final RequestPrincipal principal) {
        return eventService.generateCadence(piId, request, principal.userId(), principal.tenantId());
    }
}
