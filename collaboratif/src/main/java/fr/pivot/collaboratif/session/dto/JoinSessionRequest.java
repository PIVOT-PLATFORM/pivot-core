package fr.pivot.collaboratif.session.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/collaboratif/sessions/join} (US19.2.1) — handles both
 * authenticated (Bearer token present) and anonymous {@code ROLE_GUEST} (no Bearer token) joins
 * through this single endpoint.
 *
 * @param code        6-character session join code
 * @param displayName display name for this session (1-40 chars, escaped for display, never
 *                    interpreted as HTML)
 */
public record JoinSessionRequest(
        @NotBlank(message = "INVALID_CODE")
        @Size(min = 6, max = 6, message = "INVALID_CODE")
        String code,

        @NotBlank(message = "INVALID_DISPLAY_NAME")
        @Size(max = 40, message = "INVALID_DISPLAY_NAME")
        String displayName) {
}
