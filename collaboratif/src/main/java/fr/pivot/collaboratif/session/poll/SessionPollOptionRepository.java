package fr.pivot.collaboratif.session.poll;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link SessionPollOption}.
 */
public interface SessionPollOptionRepository extends JpaRepository<SessionPollOption, UUID> {

    /**
     * Lists a session's poll options, in display order.
     *
     * @param sessionId the owning session's UUID
     * @return the options, ordered by {@code sortOrder}
     */
    List<SessionPollOption> findAllBySessionIdOrderBySortOrderAsc(UUID sessionId);
}
