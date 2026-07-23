package fr.pivot.collaboratif.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Activity}.
 */
public interface ActivityRepository extends JpaRepository<Activity, UUID> {

    /**
     * Finds the single activity of a session.
     *
     * @param sessionId the owning session's UUID
     * @return the activity, if present
     */
    Optional<Activity> findBySessionId(UUID sessionId);
}
