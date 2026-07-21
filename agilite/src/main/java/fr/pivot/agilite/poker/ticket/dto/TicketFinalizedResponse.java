package fr.pivot.agilite.poker.ticket.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * API response shape for {@code POST
 * /api/agilite/poker/rooms/{roomId}/tickets/{ticketId}/finalize} (US09.2.3), returned with HTTP
 * 200 — a finalization transitions an existing {@code REVEALED} resource, it stays {@code
 * REVEALED} ({@code status} is unchanged by this call, only {@code finalEstimate} is newly set).
 *
 * @param id             ticket primary key
 * @param roomId         the owning room's identifier
 * @param title          the ticket's display title
 * @param status         always {@code "REVEALED"} — finalization never changes the status itself
 * @param createdAt      the ticket's original creation timestamp
 * @param revealedAt     the ticket's revelation timestamp — unaffected by finalization
 * @param finalEstimate  the facilitator-chosen final estimate, freshly persisted by this call
 */
public record TicketFinalizedResponse(
        UUID id, UUID roomId, String title, String status, Instant createdAt, Instant revealedAt,
        String finalEstimate) {
}
