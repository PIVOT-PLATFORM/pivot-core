package fr.pivot.collaboratif.session.brainstorm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link SessionBrainstormCard}.
 */
public interface SessionBrainstormCardRepository extends JpaRepository<SessionBrainstormCard, UUID> {

    /**
     * Lists a session's cards, oldest first.
     *
     * @param sessionId the owning session's UUID
     * @return the cards, oldest created first
     */
    List<SessionBrainstormCard> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Finds a card scoped to its session — the {@code sessionId} guard prevents acting on a card
     * id belonging to a different session.
     *
     * @param id        the card's UUID
     * @param sessionId the owning session's UUID
     * @return the card, if it exists within that session
     */
    Optional<SessionBrainstormCard> findByIdAndSessionId(UUID id, UUID sessionId);
}
