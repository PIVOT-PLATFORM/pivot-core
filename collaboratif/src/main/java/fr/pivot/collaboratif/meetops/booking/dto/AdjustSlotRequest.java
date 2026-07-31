package fr.pivot.collaboratif.meetops.booking.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Request body for {@code PATCH /api/collaboratif/meetings/{id}/slot} (US12.4.1) — the organizer
 * manually adjusting a proposed slot's boundaries while the meeting is still {@code
 * PRE_RESERVED}.
 *
 * @param slotId the {@code ProposedSlot} id to adjust
 * @param start  new slot start
 * @param end    new slot end — must be strictly after {@code start}, enforced in the service
 *               layer (a cross-field constraint is not expressible as a simple Bean Validation
 *               annotation here)
 */
public record AdjustSlotRequest(
        @NotNull(message = "INVALID_SLOT_ID") UUID slotId,
        @NotNull(message = "INVALID_START") Instant start,
        @NotNull(message = "INVALID_END") Instant end) {
}
