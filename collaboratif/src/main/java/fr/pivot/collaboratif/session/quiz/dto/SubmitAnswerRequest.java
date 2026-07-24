package fr.pivot.collaboratif.session.quiz.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for {@code POST /api/collaboratif/sessions/{id}/quiz/answer} (US19.3.1).
 *
 * @param questionIndex   the index of the question being answered — must match the live question
 * @param selectedIndices the participant's selected option indices
 */
public record SubmitAnswerRequest(
        @NotNull(message = "INVALID_ANSWER") Integer questionIndex,
        @NotNull(message = "INVALID_ANSWER") List<Integer> selectedIndices) {
}
