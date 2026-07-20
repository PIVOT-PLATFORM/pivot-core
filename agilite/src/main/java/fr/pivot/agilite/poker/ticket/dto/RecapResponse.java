package fr.pivot.agilite.poker.ticket.dto;

import java.util.List;
import java.util.UUID;

/**
 * API response shape for {@code GET /api/agilite/poker/rooms/{roomId}/tickets/recap} (E09 —
 * classic parity: end-of-session recap). Lists every {@code REVEALED} ticket of the room, oldest
 * first, each with its attributed votes and consensus.
 *
 * <p>Never includes the room's currently open ({@code VOTING}) ticket, if any — it has no
 * consensus yet. Accessible to any authenticated caller in the room's tenant (not
 * facilitator-restricted), mirroring {@code PokerTicketController#current}/{@code
 * PokerRoomController#findById}: every ticket it lists has already been broadcast to every
 * participant via {@code VOTES_REVEALED}, so nothing here is newly disclosed by this endpoint.
 *
 * @param roomId  the room this recap belongs to
 * @param tickets every revealed ticket, oldest revelation first
 */
public record RecapResponse(UUID roomId, List<TicketRecapEntry> tickets) {

    /**
     * Canonical constructor — defensively copies {@code tickets} into an immutable list (SpotBugs
     * EI_EXPOSE, same posture as the other list-carrying poker DTOs).
     */
    public RecapResponse {
        tickets = tickets == null ? List.of() : List.copyOf(tickets);
    }
}
