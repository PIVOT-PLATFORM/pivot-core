package fr.pivot.collaboratif.session.dto;

import tools.jackson.databind.JsonNode;
import fr.pivot.collaboratif.session.SessionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/collaboratif/sessions} (US19.1.1).
 *
 * @param title  session title (1-120 chars)
 * @param type   fixed activity type
 * @param config type-dependent configuration; only required to be non-null at creation, detailed
 *               shape validated per {@link #type} (e.g. by {@code PollActivityService})
 * @param teamId optional owning team's {@code public.teams.id} — affects visibility only
 */
public record CreateSessionRequest(
        @NotNull(message = "INVALID_TITLE")
        @Size(min = 1, max = 120, message = "INVALID_TITLE")
        String title,

        @NotNull(message = "INVALID_SESSION_TYPE")
        SessionType type,

        @NotNull(message = "INVALID_CONFIG")
        JsonNode config,

        Long teamId) {
}
