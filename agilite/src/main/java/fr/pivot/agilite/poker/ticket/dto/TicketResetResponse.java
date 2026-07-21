package fr.pivot.agilite.poker.ticket.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * API response shape for {@code POST /api/agilite/poker/rooms/{roomId}/tickets/{ticketId}/reset}
 * (US09.2.3), returned with HTTP 200 — a reset transitions an existing resource back to {@code
 * VOTING}, identical in shape to a freshly created ticket ({@link TicketResponse}) plus the
 * always-{@code null} {@code revealedAt} the AC explicitly requires in the response body.
 *
 * @param id         ticket primary key
 * @param roomId     the owning room's identifier
 * @param title      the ticket's display title
 * @param status     always {@code "VOTING"}
 * @param createdAt  the ticket's original creation timestamp — unaffected by reset
 * @param revealedAt always {@code null} — cleared by this call
 */
public record TicketResetResponse(
        UUID id, UUID roomId, String title, String status, Instant createdAt, Instant revealedAt) {
}
