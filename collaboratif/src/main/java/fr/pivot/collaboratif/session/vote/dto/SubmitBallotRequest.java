package fr.pivot.collaboratif.session.vote.dto;

import java.util.Map;

/**
 * Request body for {@code POST /api/collaboratif/sessions/{id}/vote/ballot} (US19.3.6) — a single
 * shape covering both vote types, the service validates whichever field the session's configured
 * {@link fr.pivot.collaboratif.session.vote.VoteType} requires.
 *
 * @param value       FIST_TO_FIVE: the 0-5 rating of the proposal; ignored for WEIGHTED
 * @param allocations WEIGHTED: option-index (as string) → points allocated; ignored for
 *                    FIST_TO_FIVE
 */
public record SubmitBallotRequest(Integer value, Map<String, Integer> allocations) {
}
