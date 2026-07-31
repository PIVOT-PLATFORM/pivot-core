package fr.pivot.collaboratif.meetops.booking.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/collaboratif/meetings/{id}/confirm} (US12.4.1).
 *
 * @param slotId the {@code ProposedSlot} id the organizer retained
 */
public record ConfirmSlotRequest(@NotNull(message = "INVALID_SLOT_ID") UUID slotId) {
}
