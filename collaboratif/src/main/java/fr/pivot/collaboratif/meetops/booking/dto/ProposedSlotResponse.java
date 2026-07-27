package fr.pivot.collaboratif.meetops.booking.dto;

import fr.pivot.collaboratif.meetops.booking.ProposedSlot;

import java.time.Instant;
import java.util.UUID;

/**
 * API response shape for one ranked candidate slot (US12.4.1).
 *
 * @param id              slot id — the value confirm/adjust requests reference
 * @param start           candidate slot start
 * @param end             candidate slot end
 * @param rank            {@code 1}-based rank, {@code 1} is the recommended slot
 * @param hasConflict     whether at least one participant is unavailable (or the slot could only
 *                        be found outside working hours)
 * @param conflictReason  human-readable reason, or {@code null} when not conflicted
 * @param recommended     {@code true} for the {@code rank == 1} slot — the meilleur créneau,
 *                        pre-selected by default in the validation UI (US12.4.1 AC)
 */
public record ProposedSlotResponse(
        UUID id, Instant start, Instant end, int rank, boolean hasConflict, String conflictReason,
        boolean recommended) {

    /**
     * Builds the response shape from a persisted {@link ProposedSlot}.
     *
     * @param slot the persisted entity
     * @return the response DTO
     */
    public static ProposedSlotResponse from(final ProposedSlot slot) {
        return new ProposedSlotResponse(
                slot.getId(), slot.getSlotStart(), slot.getSlotEnd(), slot.getRank(),
                slot.isHasConflict(), slot.getConflictReason(), slot.getRank() == 1);
    }
}
