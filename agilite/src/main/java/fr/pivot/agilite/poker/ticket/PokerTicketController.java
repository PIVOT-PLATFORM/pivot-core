package fr.pivot.agilite.poker.ticket;

import fr.pivot.agilite.context.RequestPrincipal;
import fr.pivot.agilite.poker.ticket.dto.CreateTicketRequest;
import fr.pivot.agilite.poker.ticket.dto.FinalizeTicketRequest;
import fr.pivot.agilite.poker.ticket.dto.RecapResponse;
import fr.pivot.agilite.poker.ticket.dto.RevealResponse;
import fr.pivot.agilite.poker.ticket.dto.TicketFinalizedResponse;
import fr.pivot.agilite.poker.ticket.dto.TicketResetResponse;
import fr.pivot.agilite.poker.ticket.dto.TicketResponse;
import fr.pivot.agilite.web.AgiliteApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing planning poker ticket operations under {@code
 * /poker/rooms/{roomId}/tickets} (US09.2.1 creation/lookup, US09.2.2 revelation, E09 recap,
 * US09.2.3 reset/finalization).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link RequestPrincipal} by {@code RequestPrincipalResolver}. Missing, malformed, or rejected
 * tokens result in HTTP 401.
 *
 * <p>The full path (including the application context) is {@code
 * /api/agilite/poker/rooms/{roomId}/tickets}.
 */
@RestController
@RequestMapping(AgiliteApiPaths.BASE + "/poker/rooms/{roomId}/tickets")
public class PokerTicketController {

    private final PokerTicketService service;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param service the ticket business logic service
     */
    public PokerTicketController(final PokerTicketService service) {
        this.service = service;
    }

    /**
     * Creates a new ticket in a room, restricted to that room's facilitator.
     *
     * @param roomId    the target room
     * @param request   the ticket creation request — non-blank title (max 200 chars)
     * @param principal the resolved caller identity (user + tenant)
     * @return the created ticket with HTTP 201 Created
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(
            @PathVariable final UUID roomId,
            @RequestBody @Valid final CreateTicketRequest request,
            final RequestPrincipal principal) {
        return service.create(roomId, request.title(), principal.userId(), principal.tenantId());
    }

    /**
     * Returns the room's currently open ({@code VOTING}) ticket, if any.
     *
     * @param roomId    the target room
     * @param principal the resolved caller identity (user + tenant)
     * @return the open ticket, or {@code null} (HTTP 200 with an empty body) if none is open
     */
    @GetMapping("/current")
    public TicketResponse current(
            @PathVariable final UUID roomId,
            final RequestPrincipal principal) {
        return service.getCurrent(roomId, principal.tenantId()).orElse(null);
    }

    /**
     * Reveals a ticket's votes and computes the consensus, restricted to that room's facilitator
     * (US09.2.2). A transition of an existing resource, not a creation — HTTP 200, not 201.
     *
     * @param roomId    the target room
     * @param ticketId  the ticket to reveal
     * @param principal the resolved caller identity (user + tenant)
     * @return the revealed ticket, its attributed votes, and the computed consensus, HTTP 200 OK
     */
    @PostMapping("/{ticketId}/reveal")
    public RevealResponse reveal(
            @PathVariable final UUID roomId,
            @PathVariable final UUID ticketId,
            final RequestPrincipal principal) {
        return service.reveal(roomId, ticketId, principal.userId(), principal.tenantId());
    }

    /**
     * Relaunches a round of voting on an already-revealed ticket, restricted to that room's
     * facilitator (US09.2.3). A transition of an existing resource — HTTP 200, not 201.
     *
     * @param roomId    the target room
     * @param ticketId  the ticket to reset
     * @param principal the resolved caller identity (user + tenant)
     * @return the reset ticket, back to {@code VOTING} with {@code revealedAt == null}, HTTP 200 OK
     */
    @PostMapping("/{ticketId}/reset")
    public TicketResetResponse reset(
            @PathVariable final UUID roomId,
            @PathVariable final UUID ticketId,
            final RequestPrincipal principal) {
        return service.reset(roomId, ticketId, principal.userId(), principal.tenantId());
    }

    /**
     * Persists the facilitator's chosen final estimate on an already-revealed ticket, restricted
     * to that room's facilitator (US09.2.3). A transition of an existing resource — HTTP 200, not
     * 201. Named {@code finalizeTicket} rather than {@code finalize} (SonarCloud java:S1175) to
     * avoid any possible confusion with {@link Object#finalize()}.
     *
     * @param roomId    the target room
     * @param ticketId  the ticket to finalize
     * @param request   the facilitator's chosen final estimate
     * @param principal the resolved caller identity (user + tenant)
     * @return the finalized ticket, still {@code REVEALED}, with {@code finalEstimate} set, HTTP
     *     200 OK
     */
    @PostMapping("/{ticketId}/finalize")
    public TicketFinalizedResponse finalizeTicket(
            @PathVariable final UUID roomId,
            @PathVariable final UUID ticketId,
            @RequestBody @Valid final FinalizeTicketRequest request,
            final RequestPrincipal principal) {
        return service.finalizeEstimate(
                roomId, ticketId, request.finalEstimate(), principal.userId(), principal.tenantId());
    }

    /**
     * Returns the room's end-of-session recap (E09 — classic parity): every already-revealed
     * ticket, oldest first, with its attributed votes and consensus. Not facilitator-restricted —
     * accessible to any authenticated caller in the room's tenant, since every ticket listed here
     * was already broadcast to every participant at its own reveal time.
     *
     * @param roomId    the target room
     * @param principal the resolved caller identity (user + tenant)
     * @return the room's recap, HTTP 200 OK — empty {@code tickets} if none has been revealed yet
     */
    @GetMapping("/recap")
    public RecapResponse recap(
            @PathVariable final UUID roomId,
            final RequestPrincipal principal) {
        return service.recap(roomId, principal.tenantId());
    }
}
