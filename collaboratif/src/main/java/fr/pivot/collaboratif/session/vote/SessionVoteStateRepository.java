package fr.pivot.collaboratif.session.vote;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionVoteState}.
 */
public interface SessionVoteStateRepository extends JpaRepository<SessionVoteState, UUID> {

    /**
     * Finds the vote-state row for a session, if one exists yet.
     *
     * @param sessionId the owning session's UUID
     * @return the state row, if present
     */
    Optional<SessionVoteState> findBySessionId(UUID sessionId);
}
