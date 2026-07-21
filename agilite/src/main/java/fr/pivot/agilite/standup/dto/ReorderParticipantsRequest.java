package fr.pivot.agilite.standup.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Request body for reordering the still-{@code WAITING} participants of a running session
 * (US10.2.2, {@code PUT .../participants/reorder}).
 *
 * <p>{@code participantIds} must be exactly the set of currently {@code WAITING} participant ids
 * (no more, no fewer, no duplicates, none already {@code SPEAKING}/{@code DONE}/{@code SKIPPED})
 * — checked at the service layer ({@code INVALID_REORDER}), since it depends on the session's
 * live state. May legitimately be an empty list when no participant is still {@code WAITING}.
 *
 * @param participantIds the new speaking order for the {@code WAITING} tail of the queue
 */
public record ReorderParticipantsRequest(@NotNull List<UUID> participantIds) {
}
