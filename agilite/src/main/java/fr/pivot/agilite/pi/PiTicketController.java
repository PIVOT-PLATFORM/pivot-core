package fr.pivot.agilite.pi;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.pi.dto.CreateTicketRequest;
import fr.pivot.agilite.pi.dto.TicketResponse;
import fr.pivot.agilite.pi.dto.UpdateTicketRequest;
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
 * REST controller exposing Program Board ticket operations under {@code
 * /pi/cycles/{cycleId}/tickets} (US50.3.1).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@link fr.pivot.agilite.context.RequestPrincipalResolver}.
 *
 * <p>{@link #update} is the same endpoint the frontend uses for a drag-drop move — see {@link
 * UpdateTicketRequest}'s Javadoc for the exact field semantics.
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/pi/cycles/{cycleId}/tickets}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/pi/cycles")
@Validated
public class PiTicketController {

    private final PiTicketService ticketService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param ticketService the Program Board ticket business logic service (US50.3.1)
     */
    public PiTicketController(final PiTicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * Creates a new ticket on a cycle's Program Board.
     *
     * @param cycleId   the cycle UUID from the path
     * @param request   the creation request
     * @param principal the resolved caller identity
     * @return the created ticket with HTTP 201 Created
     */
    @PostMapping("/{cycleId}/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(
            @PathVariable final UUID cycleId,
            @RequestBody @Valid final CreateTicketRequest request,
            final RequestPrincipal principal) {
        return ticketService.create(cycleId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Updates (or drag-drop moves) an existing ticket.
     *
     * @param cycleId   the cycle UUID from the path
     * @param ticketId  the ticket UUID from the path
     * @param request   the update request
     * @param principal the resolved caller identity
     * @return the updated ticket response
     */
    @PatchMapping("/{cycleId}/tickets/{ticketId}")
    public TicketResponse update(
            @PathVariable final UUID cycleId,
            @PathVariable final UUID ticketId,
            @RequestBody @Valid final UpdateTicketRequest request,
            final RequestPrincipal principal) {
        return ticketService.update(cycleId, ticketId, request, principal.userId(), principal.tenantId());
    }

    /**
     * Deletes a ticket — its dependencies are cascade-deleted.
     *
     * @param cycleId   the cycle UUID from the path
     * @param ticketId  the ticket UUID from the path
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/{cycleId}/tickets/{ticketId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable final UUID cycleId, @PathVariable final UUID ticketId, final RequestPrincipal principal) {
        ticketService.delete(cycleId, ticketId, principal.userId(), principal.tenantId());
    }
}
