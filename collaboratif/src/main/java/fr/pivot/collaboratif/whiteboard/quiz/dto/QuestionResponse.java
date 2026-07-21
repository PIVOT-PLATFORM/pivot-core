package fr.pivot.collaboratif.whiteboard.quiz.dto;

import fr.pivot.collaboratif.whiteboard.quiz.Question;
import fr.pivot.collaboratif.whiteboard.quiz.QuestionState;

import java.util.List;

/**
 * Wire representation of the question currently in play within a {@link
 * fr.pivot.collaboratif.whiteboard.quiz.QuizSession}.
 *
 * <p>{@code choices} deliberately carries an unbounded element type: a list of {@link
 * ChoiceResponse} (masked) while {@code state} is {@code OPEN}, or a list of {@link
 * ChoiceRevealResponse} (demasked) once {@code state} is {@code REVEALED}. Jackson serialises each
 * element by its runtime type regardless of the declared generic bound, so the wire shape is
 * exactly the caller-supplied list — this record itself performs no masking decision, it only
 * assembles what the caller (service layer, lot C1) already built. The caller is responsible for
 * choosing the right choice-DTO list to match {@code state}; see {@code ChoiceResponse} /
 * {@code ChoiceRevealResponse} for the masking guarantee itself.
 *
 * @param id             the question's UUID as a string
 * @param position       the 0-based ordering position within the session
 * @param text           the question's prompt text
 * @param state          the current question state name ({@code OPEN}/{@code REVEALED})
 * @param choices        the question's choices, masked or demasked per {@code state}
 * @param answeredCount  the number of participants who have answered this question so far
 */
public record QuestionResponse(
        String id, int position, String text, String state, List<?> choices, int answeredCount) {

    /**
     * Builds a {@link QuestionResponse} from a persisted question and its already-mapped choice
     * DTOs.
     *
     * @param question      the persisted question
     * @param state         the current state of this question ({@code OPEN} or {@code REVEALED})
     * @param choices       the question's choices already mapped to the DTO matching
     *                      {@code state} ({@link ChoiceResponse} list if {@code OPEN}, {@link
     *                      ChoiceRevealResponse} list if {@code REVEALED})
     * @param answeredCount the number of participants who have answered this question so far
     * @return the corresponding {@link QuestionResponse}
     */
    public static QuestionResponse of(
            final Question question, final QuestionState state, final List<?> choices, final int answeredCount) {
        return new QuestionResponse(
                question.getId().toString(),
                question.getPosition(),
                question.getText(),
                state.name(),
                choices,
                answeredCount);
    }
}
