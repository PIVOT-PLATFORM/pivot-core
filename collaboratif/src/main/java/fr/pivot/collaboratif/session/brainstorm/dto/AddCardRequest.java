package fr.pivot.collaboratif.session.brainstorm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/collaboratif/sessions/{id}/brainstorm/cards} (US19.3.4).
 *
 * @param text  the raw post-it text, 1-280 characters
 * @param color the post-it colour (one of the fixed palette); parsed/validated in the service
 */
public record AddCardRequest(
        @NotBlank(message = "INVALID_CARD")
        @Size(max = 280, message = "INVALID_CARD")
        String text,
        @NotNull(message = "INVALID_CARD")
        String color) {
}
