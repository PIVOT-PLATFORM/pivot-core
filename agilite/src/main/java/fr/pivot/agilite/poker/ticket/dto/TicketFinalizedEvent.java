package fr.pivot.agilite.poker.ticket.dto;

import java.util.UUID;

/**
 * {@code TICKET_FINALIZED} event broadcast to every participant on {@code
 * /topic/agilite/poker/{roomId}} (US09.2.3) — fired once, right after the facilitator validates
 * a ticket's final estimate. Every subscriber should display the "final estimate" badge and,
 * for the facilitator, retire the reset/finalize actions for this ticket (terminal state).
 *
 * <p>Shape figured for the shared room topic alongside {@code TICKET_CREATED}/{@code VOTE_CAST}/
 * {@code VOTES_REVEALED}/{@code TICKET_RESET} — this event does not modify any of those.
 *
 * @param type          always {@code "TICKET_FINALIZED"} — discriminator for the shared room topic
 * @param roomId        the room this ticket belongs to
 * @param ticketId      the finalized ticket's id
 * @param finalEstimate the facilitator-chosen final estimate
 */
public record TicketFinalizedEvent(String type, UUID roomId, UUID ticketId, String finalEstimate) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "TICKET_FINALIZED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param roomId        the room this ticket belongs to
     * @param ticketId      the finalized ticket's id
     * @param finalEstimate the facilitator-chosen final estimate
     * @return the constructed event
     */
    public static TicketFinalizedEvent of(
            final UUID roomId, final UUID ticketId, final String finalEstimate) {
        return new TicketFinalizedEvent(TYPE, roomId, ticketId, finalEstimate);
    }
}
