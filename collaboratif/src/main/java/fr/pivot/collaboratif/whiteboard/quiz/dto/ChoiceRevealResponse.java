package fr.pivot.collaboratif.whiteboard.quiz.dto;

import fr.pivot.collaboratif.whiteboard.quiz.Choice;

/**
 * Demasked wire representation of a single {@link Choice}, used once its question has been
 * {@code REVEALED}.
 *
 * <p>Carries both the sensitive {@code correct} flag and the per-choice respondent {@code count}
 * (the distribution) — fields deliberately absent from {@link ChoiceResponse}, the masked form
 * used while the question is still {@code OPEN}. The caller (service layer) is responsible for
 * only ever constructing this type after the question's state has actually transitioned to
 * {@code REVEALED} — see §2.4/§9 of {@code QUIZ-ACTIVITY-DESIGN.md}.
 *
 * @param id       the choice's UUID as a string
 * @param text     the choice's label text
 * @param position the 0-based ordering position within the question
 * @param correct  whether this choice is (one of) the correct answer(s)
 * @param count    the number of participants who selected this choice
 */
public record ChoiceRevealResponse(String id, String text, int position, boolean correct, int count) {

    /**
     * Builds a demasked {@link ChoiceRevealResponse} from a persisted choice and its tally.
     *
     * @param choice the persisted choice
     * @param count  the number of participants who selected this choice
     * @return the corresponding {@link ChoiceRevealResponse}
     */
    public static ChoiceRevealResponse of(final Choice choice, final int count) {
        return new ChoiceRevealResponse(
                choice.getId().toString(), choice.getText(), choice.getPosition(), choice.isCorrect(), count);
    }
}
