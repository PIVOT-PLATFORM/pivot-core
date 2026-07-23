package fr.pivot.collaboratif.session.brainstorm.dto;

import fr.pivot.collaboratif.session.brainstorm.BrainstormCardColor;

import java.time.Instant;
import java.util.UUID;

/**
 * Participant-safe view of a BRAINSTORM card (US19.3.4). {@code authorParticipantId} is the
 * session-scoped participant UUID (never a user id) — the client compares it to its own
 * participant id to decide whether to offer edit/delete controls; the server independently
 * enforces the same ownership rule on every mutation.
 *
 * @param id                  the card id
 * @param text                the raw card text (escaped at render time by the client)
 * @param color               the post-it colour
 * @param category            the facilitator's grouping label, or {@code null}
 * @param authorParticipantId the authoring participant's session-scoped id
 * @param createdAt           the creation timestamp
 */
public record BrainstormCardDto(
        UUID id,
        String text,
        BrainstormCardColor color,
        String category,
        UUID authorParticipantId,
        Instant createdAt) {
}
