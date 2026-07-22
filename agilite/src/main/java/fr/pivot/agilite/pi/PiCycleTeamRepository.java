package fr.pivot.agilite.pi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PiCycleTeam} entities (US50.1.1).
 */
public interface PiCycleTeamRepository extends JpaRepository<PiCycleTeam, UUID> {

    /**
     * Finds a Train team by id, scoped to the expected cycle.
     *
     * @param id      the Train team UUID
     * @param cycleId the expected owning cycle's UUID
     * @return the matching Train team, or empty if not found or owned by another cycle
     */
    Optional<PiCycleTeam> findByIdAndCycleId(UUID id, UUID cycleId);
}
