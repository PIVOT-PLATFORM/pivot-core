package fr.pivot.collaboratif.whiteboard.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Choice} entities (Quiz feature).
 *
 * <p>Choices are scoped by {@code questionId}; tenant/board isolation is guaranteed one layer up
 * because the owning {@link Question}/{@link QuizSession} is only ever obtained through a lookup
 * scoped {@code (id, boardId, tenantId)} (see {@link QuizSessionRepository}).
 */
public interface ChoiceRepository extends JpaRepository<Choice, UUID> {

    /**
     * Returns every choice belonging to any of the given questions, ordered by their 0-based
     * position — used to batch-load choices for a whole question set (e.g. building the
     * facilitator's reveal payload or the participant's masked view).
     *
     * @param questionIds the owning questions' UUIDs
     * @return the matching choices, ordered by position; empty if none match
     */
    List<Choice> findAllByQuestionIdInOrderByPositionAsc(Collection<UUID> questionIds);

    /**
     * Counts whether a choice id belongs to a specific question — the membership check performed
     * before accepting an answer, so a {@code choiceId} from a different question (or a stale one
     * from a previous question) is rejected. A result other than {@code 1} means "does not belong".
     *
     * @param id         the choice UUID supplied by the client
     * @param questionId the question the choice must belong to (the current question)
     * @return {@code 1} if the choice exists and belongs to that question, {@code 0} otherwise
     */
    long countByIdAndQuestionId(UUID id, UUID questionId);
}
