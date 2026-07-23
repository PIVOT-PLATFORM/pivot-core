package fr.pivot.collaboratif.session.qa.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/collaboratif/sessions/{id}/qa/questions} (US19.3.5).
 *
 * @param text      the raw question text, 1-500 characters
 * @param anonymous whether to withhold the author's name from other participants; a {@code null}
 *                  JSON value is treated as {@code false} (see {@link #isAnonymous()})
 */
public record SubmitQuestionRequest(
        @NotBlank(message = "INVALID_QUESTION")
        @Size(max = 500, message = "INVALID_QUESTION")
        String text,
        Boolean anonymous) {

    /**
     * Returns whether the question is anonymous, defaulting a missing/{@code null} value to
     * {@code false}.
     *
     * @return {@code true} when the author's name must be withheld
     */
    public boolean isAnonymous() {
        return Boolean.TRUE.equals(anonymous);
    }
}
