package fr.pivot.collaboratif.meetops.bestslot;

import java.time.Instant;

/**
 * One ranked candidate produced by {@link BestSlotEngine} (US12.4.1), before persistence as a
 * {@code ProposedSlot} — the engine itself is persistence-agnostic (unit-testable without a
 * database).
 *
 * @param start           candidate slot start
 * @param end             candidate slot end
 * @param availableCount  number of participants free for this slot
 * @param totalCount      total number of participants considered
 * @param hasConflict     {@code true} when {@code availableCount < totalCount}, or the slot could
 *                        only be generated outside working hours (no fully-clean candidate at all
 *                        existed in the window)
 * @param conflictReason  human-readable reason, or {@code null} when {@code !hasConflict}
 */
public record SlotCandidate(
        Instant start, Instant end, int availableCount, int totalCount, boolean hasConflict,
        String conflictReason) {
}
