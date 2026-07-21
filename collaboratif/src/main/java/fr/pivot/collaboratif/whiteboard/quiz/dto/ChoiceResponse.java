package fr.pivot.collaboratif.whiteboard.quiz.dto;

import fr.pivot.collaboratif.whiteboard.quiz.Choice;

/**
 * Masked wire representation of a single {@link Choice}, used while its question is
 * {@code OPEN} (not yet revealed).
 *
 * <p>⚠️ Masking by construction: this record deliberately has no {@code correct} field and no
 * per-choice {@code count} — a question that is not yet {@code REVEALED} must never let a
 * participant learn the correct answer or infer it from the distribution (see
 * {@code ChoiceRevealResponse} for the post-reveal counterpart, and §2.4/§9 of
 * {@code QUIZ-ACTIVITY-DESIGN.md} for the rationale). There is no way to add {@code correct} to
 * this type without touching this file, which is the point.
 *
 * @param id       the choice's UUID as a string
 * @param text     the choice's label text
 * @param position the 0-based ordering position within the question
 */
public record ChoiceResponse(String id, String text, int position) {

    /**
     * Builds a masked {@link ChoiceResponse} from a persisted choice, omitting {@code correct}.
     *
     * @param choice the persisted choice
     * @return the corresponding masked {@link ChoiceResponse}
     */
    public static ChoiceResponse of(final Choice choice) {
        return new ChoiceResponse(choice.getId().toString(), choice.getText(), choice.getPosition());
    }
}
