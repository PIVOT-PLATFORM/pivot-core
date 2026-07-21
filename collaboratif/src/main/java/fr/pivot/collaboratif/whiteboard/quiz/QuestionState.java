package fr.pivot.collaboratif.whiteboard.quiz;

/**
 * State of the question currently being played within a {@link QuizSession}.
 *
 * <p>Carried by {@link QuizSession#getCurrentState()} for the question at
 * {@link QuizSession#getCurrentQuestionIndex()}. The MVP ("Quiz QCM animé par le facilitateur")
 * drives this field through {@link #OPEN} (participants may answer) and {@link #REVEALED}
 * (correct choices, distribution and scores are broadcast) only. {@link #PENDING} and
 * {@link #CLOSED} are pre-provisioned for a future per-question lifecycle (e.g. explicit closing
 * before reveal, or a not-yet-opened state ahead of the current index) but are not set by the MVP
 * application logic.
 */
public enum QuestionState {

    /** The question exists but has not been opened for answers yet. Not used by the MVP. */
    PENDING,

    /** The question is live: participants may submit/upsert an answer. */
    OPEN,

    /** The question is no longer accepting answers but has not been revealed. Not used by the
     * MVP (which transitions OPEN directly to REVEALED). */
    CLOSED,

    /** The question's correct choice(s), per-choice distribution and cumulative scores have been
     * broadcast to all participants. */
    REVEALED
}
