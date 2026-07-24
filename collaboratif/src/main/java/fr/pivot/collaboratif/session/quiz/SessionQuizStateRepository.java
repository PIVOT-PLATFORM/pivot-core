package fr.pivot.collaboratif.session.quiz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionQuizState}.
 */
public interface SessionQuizStateRepository extends JpaRepository<SessionQuizState, UUID> {

    /**
     * Finds the progression state for a session, if the quiz has been touched yet.
     *
     * @param sessionId the owning session's UUID
     * @return the state row, if present
     */
    Optional<SessionQuizState> findBySessionId(UUID sessionId);
}
