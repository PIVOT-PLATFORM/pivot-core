package fr.pivot.agilite.pi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PiCycle} entities (US50.1.1).
 */
public interface PiCycleRepository extends JpaRepository<PiCycle, UUID> {

    /**
     * Finds a cycle by its identifier, verifying it belongs to the given tenant.
     *
     * <p>Returns {@link Optional#empty()} if the cycle does not exist or belongs to a different
     * tenant, preventing cross-tenant information disclosure.
     *
     * @param id       the cycle UUID
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} containing the cycle, or empty if not found
     */
    Optional<PiCycle> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Finds every cycle created by a given user, scoped to the given tenant.
     *
     * @param createdBy the creator's {@code public.users.id}
     * @param tenantId  the expected tenant's {@code public.tenants.id}
     * @return the caller's own cycles
     */
    List<PiCycle> findAllByCreatedByAndTenantId(Long createdBy, Long tenantId);

    /**
     * Finds every cycle, scoped to the given tenant, that has at least one Train team imported
     * from one of the given source teams — i.e. cycles accessible via team membership rather
     * than creatorship. Only called with a non-empty {@code sourceTeamIds} (an empty {@code IN}
     * list is guarded in the service layer, not relied upon here).
     *
     * @param tenantId      the expected tenant's {@code public.tenants.id}
     * @param sourceTeamIds the caller's {@code public.teams.id} memberships
     * @return the matching cycles (may overlap with the caller's own created cycles — deduplicated
     *     by the caller)
     */
    @Query("SELECT DISTINCT c FROM PiCycle c JOIN c.teams t "
            + "WHERE c.tenantId = :tenantId AND t.sourceTeamId IN :sourceTeamIds")
    List<PiCycle> findAllByTenantIdAndTeamSourceTeamIdIn(
            @Param("tenantId") Long tenantId, @Param("sourceTeamIds") List<Long> sourceTeamIds);
}
