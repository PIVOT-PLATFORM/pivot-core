package fr.pivot.collaboratif.whiteboard.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Answer} entities (Quiz feature).
 *
 * <p>Unlike {@code VoteRepository} (dot-voting stacks, no per-user uniqueness), a quiz answer is
 * unique per {@code (sessionId, questionId, userId)} — {@code uq_quiz_answer_once} in
 * {@code V9__quiz.sql}. {@link #findBySessionIdAndQuestionIdAndUserId} is the read side of the
 * upsert the application layer performs against that constraint.
 */
public interface AnswerRepository extends JpaRepository<Answer, UUID> {

    /**
     * Returns a user's existing answer to a question within a session, if any — the lookup
     * performed before an upsert: present ⇒ update the existing row's choice (and, once wired,
     * {@code answeredAt}); absent ⇒ insert a new answer.
     *
     * @param sessionId  the session UUID
     * @param questionId the targeted question UUID
     * @param userId     the answering user's {@code public.users.id}
     * @return the user's existing answer to that question, or empty if they have not answered yet
     */
    Optional<Answer> findBySessionIdAndQuestionIdAndUserId(UUID sessionId, UUID questionId, Long userId);

    /**
     * Returns every answer recorded in a session — used to build the per-choice tally at reveal
     * and the cumulative leaderboard.
     *
     * @param sessionId the session UUID
     * @return the session's answers; empty if none were recorded
     */
    List<Answer> findAllBySessionId(UUID sessionId);

    /**
     * Counts how many participants have answered a given question in a session — the
     * "{@code answeredCount}" broadcast while the question is {@link QuestionState#OPEN}, which
     * deliberately exposes only the respondent count, never the per-choice distribution (that
     * would leak the answer shape before reveal).
     *
     * @param sessionId  the session UUID
     * @param questionId the targeted question UUID
     * @return the number of distinct answers recorded for that question
     */
    long countBySessionIdAndQuestionId(UUID sessionId, UUID questionId);
}
