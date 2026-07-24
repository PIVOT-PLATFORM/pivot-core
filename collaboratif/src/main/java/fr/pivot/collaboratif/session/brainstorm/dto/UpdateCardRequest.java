package fr.pivot.collaboratif.session.brainstorm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/collaboratif/sessions/{id}/brainstorm/cards/{cardId}}
 * (US19.3.4) — author edit of their own card.
 *
 * @param text  the new post-it text, 1-280 characters
 * @param color the new post-it colour (one of the fixed palette)
 */
public record UpdateCardRequest(
        @NotBlank(message = "INVALID_CARD")
        @Size(max = 280, message = "INVALID_CARD")
        String text,
        @NotNull(message = "INVALID_CARD")
        String color) {
}
