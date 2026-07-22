package fr.pivot.agilite.pi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PiIteration} entities (US50.1.1).
 */
public interface PiIterationRepository extends JpaRepository<PiIteration, UUID> {

    /**
     * Finds an iteration by id, scoped to the expected cycle.
     *
     * @param id      the iteration UUID
     * @param cycleId the expected owning cycle's UUID
     * @return the matching iteration, or empty if not found or owned by another cycle
     */
    Optional<PiIteration> findByIdAndCycleId(UUID id, UUID cycleId);
}
