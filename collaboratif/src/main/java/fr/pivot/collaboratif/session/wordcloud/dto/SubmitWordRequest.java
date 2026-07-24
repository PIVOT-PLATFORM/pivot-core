package fr.pivot.collaboratif.session.wordcloud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/collaboratif/sessions/{id}/wordcloud/words} (US19.3.3).
 *
 * @param word the raw submitted word, 1-30 characters before normalization
 */
public record SubmitWordRequest(
        @NotBlank(message = "INVALID_WORD")
        @Size(max = 30, message = "INVALID_WORD")
        String word) {
}
