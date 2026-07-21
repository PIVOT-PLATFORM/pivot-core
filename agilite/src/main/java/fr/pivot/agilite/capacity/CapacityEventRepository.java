package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link CapacityEvent} (E11 — capacity planning), schema {@code
 * agilite}.
 */
public interface CapacityEventRepository extends JpaRepository<CapacityEvent, UUID> {

    /**
     * Finds an event by id, scoped to the given tenant — the transversal tenant-isolation
     * pattern used across this repo's endpoints (see {@code CLAUDE.md}): an event belonging to
     * another tenant is treated identically to a non-existent event.
     *
     * @param id       the event's primary key
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the matching event, or empty if it does not exist or belongs to another tenant
     */
    Optional<CapacityEvent> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Finds every event of a team, scoped to the given tenant.
     *
     * @param teamId   the owning team's identifier
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the team's events, in no particular order
     */
    List<CapacityEvent> findByTeamIdAndTenantId(Long teamId, Long tenantId);

    /**
     * Finds every direct child of a parent event (e.g. a PI's sprints), scoped to the given
     * tenant.
     *
     * @param parentId the parent event's identifier
     * @param tenantId the caller's tenant id, resolved server-side from the bearer token
     * @return the parent's direct children, in no particular order
     */
    List<CapacityEvent> findByParentIdAndTenantId(UUID parentId, Long tenantId);
}
