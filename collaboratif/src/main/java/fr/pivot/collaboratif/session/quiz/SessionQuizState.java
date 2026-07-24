package fr.pivot.collaboratif.session.quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The live progression of a QUIZ-type session (US19.3.1) — one row per session, tracking which
 * question is currently on screen, when it started (the authoritative clock for late-answer
 * rejection and speed-bonus ranking), and whether it has been ended/revealed.
 *
 * <p>{@code currentQuestionIndex} is {@code -1} before the first {@code next}. Timing is
 * server-authoritative: the client's countdown is display-only, the backend alone decides whether
 * an answer arrived within the question's window.
 */
@Entity
@Table(name = "session_quiz_state", schema = "collaboratif")
public class SessionQuizState {

    /** The owning session's id, and this row's primary key. */
    @Id
    @Column(name = "session_id")
    private UUID sessionId;

    /** Index of the question currently on screen; {@code -1} before the first question. */
    @Column(name = "current_question_index", nullable = false)
    private int currentQuestionIndex;

    /** When the current question was started — the clock for the answer window. */
    @Column(name = "question_started_at")
    private Instant questionStartedAt;

    /** Whether the current question has been ended (correct answer revealed, leaderboard sent). */
    @Column(name = "question_ended", nullable = false)
    private boolean questionEnded;

    /** No-arg constructor required by JPA. */
    protected SessionQuizState() {
    }

    /**
     * Creates the initial, not-yet-started state.
     *
     * @param sessionId the owning session's UUID
     */
    public SessionQuizState(final UUID sessionId) {
        this.sessionId = sessionId;
        this.currentQuestionIndex = -1;
        this.questionEnded = true;
    }

    /**
     * Advances to a question and (re)opens its answer window.
     *
     * @param questionIndex the question now on screen
     * @param now           its start timestamp
     */
    public void startQuestion(final int questionIndex, final Instant now) {
        this.currentQuestionIndex = questionIndex;
        this.questionStartedAt = now;
        this.questionEnded = false;
    }

    /** Ends the current question (answers no longer accepted). */
    public void endQuestion() {
        this.questionEnded = true;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public int getCurrentQuestionIndex() {
        return currentQuestionIndex;
    }

    public Instant getQuestionStartedAt() {
        return questionStartedAt;
    }

    public boolean isQuestionEnded() {
        return questionEnded;
    }
}
