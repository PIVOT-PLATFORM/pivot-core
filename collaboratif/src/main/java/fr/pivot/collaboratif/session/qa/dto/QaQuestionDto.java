package fr.pivot.collaboratif.session.qa.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Participant-safe view of a Q&amp;A question (US19.3.5).
 *
 * <p>{@code authorName} is {@code null} when the question was submitted anonymously — the author's
 * display name is withheld from every other participant, never sent over the wire. The authoring
 * {@code participantId} is deliberately not exposed here for the same reason.
 *
 * @param id        the question id
 * @param text      the raw question text (escaped at render time by the client)
 * @param authorName the author's display name, or {@code null} when anonymous
 * @param anonymous whether the question was submitted anonymously
 * @param answered  whether the facilitator has marked it answered
 * @param upvotes   the current upvote count
 * @param createdAt the creation timestamp
 */
public record QaQuestionDto(
        UUID id,
        String text,
        String authorName,
        boolean anonymous,
        boolean answered,
        long upvotes,
        Instant createdAt) {
}
