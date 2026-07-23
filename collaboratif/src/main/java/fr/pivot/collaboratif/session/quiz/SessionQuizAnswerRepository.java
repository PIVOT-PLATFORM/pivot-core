package fr.pivot.collaboratif.session.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionQuizAnswer}.
 */
public interface SessionQuizAnswerRepository extends JpaRepository<SessionQuizAnswer, UUID> {

    /**
     * Returns whether a participant has already answered a given question (the one-answer guard).
     *
     * @param sessionId     the owning session's UUID
     * @param participantId the participant's UUID
     * @param questionIndex the question index
     * @return {@code true} if an answer already exists
     */
    boolean existsBySessionIdAndParticipantIdAndQuestionIndex(
            UUID sessionId, UUID participantId, int questionIndex);

    /**
     * Counts the correct answers already recorded for a question — the basis of the speed-bonus
     * submission rank (the next correct answer's rank is this count + 1).
     *
     * @param sessionId     the owning session's UUID
     * @param questionIndex the question index
     * @return the number of correct answers so far
     */
    long countBySessionIdAndQuestionIndexAndCorrectTrue(UUID sessionId, int questionIndex);

    /**
     * Lists every answer for a question — used to compute its correct-rate.
     *
     * @param sessionId     the owning session's UUID
     * @param questionIndex the question index
     * @return the answers to that question
     */
    List<SessionQuizAnswer> findAllBySessionIdAndQuestionIndex(UUID sessionId, int questionIndex);

    /**
     * Lists every answer cast in the session — used to tally the leaderboard.
     *
     * @param sessionId the owning session's UUID
     * @return every answer in the session
     */
    List<SessionQuizAnswer> findAllBySessionId(UUID sessionId);

    /**
     * Finds a participant's own answer to a question — used to rebuild their reconnect state.
     *
     * @param sessionId     the owning session's UUID
     * @param participantId the participant's UUID
     * @param questionIndex the question index
     * @return the participant's answer, if any
     */
    Optional<SessionQuizAnswer> findBySessionIdAndParticipantIdAndQuestionIndex(
            UUID sessionId, UUID participantId, int questionIndex);
}
