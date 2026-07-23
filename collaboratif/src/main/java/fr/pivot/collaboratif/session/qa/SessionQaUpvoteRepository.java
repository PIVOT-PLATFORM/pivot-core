package fr.pivot.collaboratif.session.qa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link SessionQaUpvote}.
 */
public interface SessionQaUpvoteRepository extends JpaRepository<SessionQaUpvote, UUID> {

    /**
     * Returns whether a participant has already upvoted a question — the guard behind the
     * one-upvote-per-participant rule (US19.3.5, double upvote → 409).
     *
     * @param questionId    the question's UUID
     * @param participantId the participant's UUID
     * @return {@code true} if an upvote already exists
     */
    boolean existsByQuestionIdAndParticipantId(UUID questionId, UUID participantId);

    /**
     * Counts the upvotes on a single question.
     *
     * @param questionId the question's UUID
     * @return the number of upvotes
     */
    long countByQuestionId(UUID questionId);

    /**
     * Lists every upvote across a set of questions, used to tally a whole session's list in one
     * query rather than N per-question counts.
     *
     * @param questionIds the question UUIDs to tally
     * @return the matching upvotes
     */
    List<SessionQaUpvote> findAllByQuestionIdIn(List<UUID> questionIds);
}
