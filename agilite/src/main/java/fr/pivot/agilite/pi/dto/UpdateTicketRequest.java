package fr.pivot.agilite.pi.dto;

import fr.pivot.agilite.pi.PiTicketType;

import java.util.UUID;

/**
 * Request body for updating (or drag-drop moving) a Program Board ticket (US50.3.1). This is the
 * same endpoint the frontend uses for a drag-drop move: supplying new {@code teamId}/{@code
 * iterationId}/{@code order} relocates the ticket, no separate {@code /move} endpoint exists.
 *
 * <p><strong>Field semantics.</strong> {@code type}/{@code title}/{@code description}/{@code
 * order}: standard partial-update convention, {@code null} (omitted) leaves the field unchanged.
 * {@code teamId}/{@code iterationId}: always applied verbatim, including {@code null} (which
 * means the dedicated Train row / the "Unplanned" column respectively) — these two cannot use the
 * "null means unchanged" convention since {@code null} is itself a valid target cell coordinate,
 * and a drag-drop move always supplies the ticket's full target cell together. Plain edits (title/
 * description/type only, from the detail view) simply resend the ticket's current {@code teamId}/
 * {@code iterationId} alongside the changed field(s).
 *
 * @param type        new ticket type, or {@code null} to leave unchanged
 * @param title       new title, or {@code null} to leave unchanged
 * @param description new description, or {@code null} to leave unchanged
 * @param teamId      target team, or {@code null} for the Train row — always applied
 * @param iterationId target iteration, or {@code null} for "Unplanned" — always applied
 * @param order       new display order within the target cell, or {@code null} to leave unchanged
 */
public record UpdateTicketRequest(
        PiTicketType type,
        String title,
        String description,
        UUID teamId,
        UUID iterationId,
        Integer order) {
}
