package fr.pivot.collaboratif.session.brainstorm.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/collaboratif/sessions/{id}/brainstorm/cards/{cardId}/category}
 * (US19.3.4) — facilitator grouping. A {@code null}/blank category clears the grouping.
 *
 * @param category the grouping label, up to 80 characters, or {@code null} to clear
 */
public record CategorizeCardRequest(
        @Size(max = 80, message = "INVALID_CATEGORY")
        String category) {
}
