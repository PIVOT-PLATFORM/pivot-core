package fr.pivot.collaboratif.session.poll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionPollState}.
 */
public interface SessionPollStateRepository extends JpaRepository<SessionPollState, UUID> {

    /**
     * Finds the hide/show state row for a session.
     *
     * @param sessionId the owning session's UUID
     * @return the state, if a row exists (absent = results visible)
     */
    Optional<SessionPollState> findBySessionId(UUID sessionId);
}
