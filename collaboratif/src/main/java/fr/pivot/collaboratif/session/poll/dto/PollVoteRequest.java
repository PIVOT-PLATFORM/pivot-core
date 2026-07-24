package fr.pivot.collaboratif.session.poll.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/**
 * Request body for {@code POST /api/collaboratif/sessions/{id}/poll/vote} (US19.3.2).
 *
 * @param optionIds selected option ids — exactly 1 unless the poll's {@code allowMultiple} is set
 */
public record PollVoteRequest(
        @NotEmpty(message = "INVALID_POLL_VOTE")
        List<UUID> optionIds) {
}
