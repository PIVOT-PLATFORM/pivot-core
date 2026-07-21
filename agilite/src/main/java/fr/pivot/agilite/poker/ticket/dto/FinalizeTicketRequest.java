package fr.pivot.agilite.poker.ticket.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/agilite/poker/rooms/{roomId}/tickets/{ticketId}/finalize}
 * (US09.2.3).
 *
 * <p>{@code finalEstimate} membership in the room's own deck is validated by {@code
 * PokerTicketService#finalizeEstimate}, not here — unlike {@link CreateTicketRequest}'s title,
 * the set of accepted values depends on the room's deck (E09 — {@code PokerCardDeck}), which this
 * DTO has no access to. This annotation only rejects an absent/blank value up front.
 *
 * @param finalEstimate the facilitator-chosen final estimate — required, membership in the
 *                       room's deck checked at the service layer
 */
public record FinalizeTicketRequest(
        @NotBlank(message = "INVALID_FINAL_ESTIMATE")
        String finalEstimate) {
}
