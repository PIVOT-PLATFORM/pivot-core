package fr.pivot.agilite.pi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PiDependency} entities (US50.3.2).
 */
public interface PiDependencyRepository extends JpaRepository<PiDependency, UUID> {

    /**
     * Finds a dependency by id, scoped to the expected cycle.
     *
     * @param id      the dependency UUID
     * @param cycleId the expected owning cycle's UUID
     * @return the matching dependency, or empty if not found or owned by another cycle
     */
    Optional<PiDependency> findByIdAndCycleId(UUID id, UUID cycleId);

    /**
     * Lists every dependency of a cycle — the full edge set consumed by {@link
     * PiDependencyCycleDetector} before inserting a new edge.
     *
     * @param cycleId the owning cycle's UUID
     * @return the cycle's dependencies
     */
    List<PiDependency> findAllByCycleId(UUID cycleId);

    /**
     * Checks whether a dependency already exists for a given ordered pair of tickets (same
     * direction — {@code A -> B} does not collide with {@code B -> A}).
     *
     * @param fromTicketId the source ticket
     * @param toTicketId   the target ticket
     * @return {@code true} if a dependency with this exact direction already exists
     */
    boolean existsByFromTicketIdAndToTicketId(UUID fromTicketId, UUID toTicketId);
}
