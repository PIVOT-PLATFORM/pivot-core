package fr.pivot.agilite.capacity;

import fr.pivot.agilite.capacity.dto.CapacitySummaryResponse;
import fr.pivot.agilite.capacity.dto.CreateEventRequest;
import fr.pivot.agilite.capacity.dto.EventRef;
import fr.pivot.agilite.capacity.dto.EventResponse;
import fr.pivot.agilite.capacity.dto.UpdateEventRequest;
import fr.pivot.agilite.context.RequestPrincipal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing capacity event CRUD and PI/Sprint hierarchy operations under {@code
 * /capacity/events} (US11.1.1/US11.3.1).
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
    private final CapacitySummaryService summaryService;

    /**
     * Creates the controller with its required service dependencies.
     *
     * @param eventService   the capacity event business logic service (US11.1.1)
     * @param summaryService the capacity summary business logic service (US11.1.2)
     */
    public CapacityEventController(final CapacityEventService eventService, final CapacitySummaryService summaryService) {
        this.eventService = eventService;
        this.summaryService = summaryService;
    }

    /**
     * Creates a new capacity event.
     *
     * @param request   the creation request
     * @param principal the resolved caller identity
     * @return the created event with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@RequestBody @Valid final CreateEventRequest request, final RequestPrincipal principal) {
        return eventService.create(request, principal.userId(), principal.tenantId());
    }

    /**
     * Lists the events accessible to the caller, optionally filtered by team and/or type.
     *
     * @param teamId    optional team filter
     * @param type      optional type filter
     * @param principal the resolved caller identity
     * @return the accessible events, most recent {@code startDate} first
     */
    @GetMapping
    public List<EventResponse> list(
            @RequestParam(required = false) final Long teamId,
            @RequestParam(required = false) final CapacityEventType type,
            final RequestPrincipal principal) {
        return eventService.list(teamId, type, principal.userId(), principal.tenantId());
    }

    /**
     * Returns a single event by its identifier, if the caller has access.
     *
     * @param eventId   the event UUID from the path
     * @param principal the resolved caller identity
     * @return the event, or HTTP 404 if not found or inaccessible
     */
    @GetMapping("/{eventId}")
    public EventResponse findById(@PathVariable final UUID eventId, final RequestPrincipal principal) {
        return eventService.findById(eventId, principal.userId(), principal.tenantId());
    }

    /**
     * Lists a PI Planning event's direct children (US11.3.1).
     *
     * @param eventId   the PI Planning event's UUID from the path
     * @param principal the resolved caller identity
     * @return the children summaries, ordered by {@code startDate} ascending
     */
    @GetMapping("/{eventId}/children")
    public List<EventRef> children(@PathVariable final UUID eventId, final RequestPrincipal principal) {
        return eventService.listChildren(eventId, principal.userId(), principal.tenantId());
    }

    /**
     * Returns an event's provisional capacity summary (US11.1.2) — a leaf computation, or a
     * PI Planning aggregation over its children (US11.3.1).
     *
     * @param eventId   the event UUID from the path
     * @param principal the resolved caller identity
     * @return the computed summary
     */
    @GetMapping("/{eventId}/summary")
    public CapacitySummaryResponse summary(@PathVariable final UUID eventId, final RequestPrincipal principal) {
        return summaryService.getSummary(eventId, principal.userId(), principal.tenantId());
    }

    /**
     * Updates an event's own fields.
     *
     * @param eventId   the event UUID from the path
     * @param request   the update request
     * @param principal the resolved caller identity
     * @return the updated event response
     */
    @PatchMapping("/{eventId}")
    public EventResponse update(
            @PathVariable final UUID eventId,
            @RequestBody @Valid final UpdateEventRequest request,
            final RequestPrincipal principal) {
        return eventService.update(eventId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Permanently deletes an event and its roster/absences/burndown entries — refused if it
     * still has children.
     *
     * @param eventId   the event UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable final UUID eventId, final RequestPrincipal principal) {
        eventService.delete(eventId, principal.userId(), principal.tenantId());
    }
}
