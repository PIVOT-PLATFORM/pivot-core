package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link CapacityVelocity} (E11 — capacity planning), schema {@code
 * agilite}.
 *
 * <p>Not directly tenant-scoped — same rationale as {@link CapacityEventMemberRepository}.
 */
public interface CapacityVelocityRepository extends JpaRepository<CapacityVelocity, UUID> {

    /**
     * Finds the velocity snapshot of a sprint, if it has been closed out.
     *
     * @param sprintEventId the sprint's identifier
     * @return the matching snapshot, or empty if the sprint has not been closed yet
     */
    Optional<CapacityVelocity> findBySprintEventId(UUID sprintEventId);

    /**
     * Finds the velocity snapshots of several sprints, in a single query — used by the (future)
     * calculator/service layer to build {@code CapacityCalculator}'s rolling-window velocity
     * history.
     *
     * @param sprintEventIds the sprints' identifiers, typically the last N sprints of a team,
     *                       most recent first
     * @return the matching snapshots, in no particular order
     */
    List<CapacityVelocity> findBySprintEventIdIn(List<UUID> sprintEventIds);
}
