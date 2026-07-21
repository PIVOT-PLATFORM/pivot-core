package fr.pivot.agilite.poker.ticket.dto;

import java.util.UUID;

/**
 * {@code TICKET_RESET} event broadcast to every participant on {@code
 * /topic/agilite/poker/{roomId}} (US09.2.3) — fired once, right after the facilitator resets an
 * already-revealed ticket back to {@code VOTING}. Every subscriber (facilitator included) must
 * locally clear any previously selected/displayed vote and revert to the "waiting to vote" state
 * — identical treatment to a brand-new {@code TICKET_CREATED} ticket.
 *
 * <p>Shape figured for the shared room topic alongside {@code TICKET_CREATED}/{@code VOTE_CAST}/
 * {@code VOTES_REVEALED} — this event does not modify any of those.
 *
 * @param type     always {@code "TICKET_RESET"} — discriminator for the shared room topic
 * @param roomId   the room this ticket belongs to
 * @param ticketId the reset ticket's id
 */
public record TicketResetEvent(String type, UUID roomId, UUID ticketId) {

    /**
     * Discriminator value for this event type. Named {@code EVENT_TYPE} rather than {@code TYPE}
     * (SonarCloud java:S1845) to avoid any case-only clash with the record component {@link
     * #type()}.
     */
    public static final String EVENT_TYPE = "TICKET_RESET";

    /**
     * Builds the event with {@link #EVENT_TYPE} as its discriminator.
     *
     * @param roomId   the room this ticket belongs to
     * @param ticketId the reset ticket's id
     * @return the constructed event
     */
    public static TicketResetEvent of(final UUID roomId, final UUID ticketId) {
        return new TicketResetEvent(EVENT_TYPE, roomId, ticketId);
    }
}
