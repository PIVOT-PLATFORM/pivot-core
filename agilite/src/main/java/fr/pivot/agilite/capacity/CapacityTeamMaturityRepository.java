package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CapacityTeamMaturity} entities (US11.6.4).
 */
public interface CapacityTeamMaturityRepository extends JpaRepository<CapacityTeamMaturity, UUID> {

    /**
     * Finds a team's current maturity row.
     *
     * @param teamId   the team's {@code public.teams.id}
     * @param tenantId the expected owning tenant's id
     * @return the matching row, or empty if the team has no maturity configured
     */
    Optional<CapacityTeamMaturity> findByTeamIdAndTenantId(Long teamId, Long tenantId);
}
