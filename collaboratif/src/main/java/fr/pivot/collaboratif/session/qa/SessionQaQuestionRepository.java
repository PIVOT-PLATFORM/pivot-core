package fr.pivot.collaboratif.session.qa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionQaQuestion}.
 */
public interface SessionQaQuestionRepository extends JpaRepository<SessionQaQuestion, UUID> {

    /**
     * Lists every question of a session, oldest first — the stable secondary ordering the service
     * applies its upvote-descending sort on top of.
     *
     * @param sessionId the owning session's UUID
     * @return the questions, oldest created first
     */
    List<SessionQaQuestion> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Finds a question scoped to its session — the {@code sessionId} guard prevents acting on a
     * question id belonging to a different session.
     *
     * @param id        the question's UUID
     * @param sessionId the owning session's UUID
     * @return the question, if it exists within that session
     */
    Optional<SessionQaQuestion> findByIdAndSessionId(UUID id, UUID sessionId);
}
