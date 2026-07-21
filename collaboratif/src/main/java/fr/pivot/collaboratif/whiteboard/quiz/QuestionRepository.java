package fr.pivot.collaboratif.whiteboard.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Question} entities (Quiz feature).
 *
 * <p>Questions are scoped by {@code sessionId} only; tenant/board isolation is guaranteed one
 * layer up because the owning {@link QuizSession} is only ever obtained through a lookup scoped
 * {@code (id, boardId, tenantId)} (see {@link QuizSessionRepository}).
 */
public interface QuestionRepository extends JpaRepository<Question, UUID> {

    /**
     * Returns every question of a session, ordered by their 0-based position — used to build the
     * facilitator's question set and to resolve the question at a given index.
     *
     * @param sessionId the session UUID
     * @return the session's questions, ordered by position; empty if the session has none
     */
    List<Question> findAllBySessionIdOrderByPositionAsc(UUID sessionId);

    /**
     * Returns the question at a given 0-based position within a session, if any — used to resolve
     * the current question from {@link QuizSession#getCurrentQuestionIndex()}.
     *
     * @param sessionId the session UUID
     * @param position  the 0-based position within the session
     * @return the matching question, or empty if no question exists at that position
     */
    Optional<Question> findBySessionIdAndPosition(UUID sessionId, int position);
}
