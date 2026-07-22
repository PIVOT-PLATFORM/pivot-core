package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CapacityEvent} entities (US11.1.1).
 */
public interface CapacityEventRepository extends JpaRepository<CapacityEvent, UUID> {

    /**
     * Finds an event by id, scoped to the expected tenant.
     *
     * @param id       the event UUID
     * @param tenantId the expected owning tenant's id
     * @return the matching event, or empty if not found or owned by another tenant
     */
    Optional<CapacityEvent> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Lists every event belonging to a tenant, most recent {@code startDate} first — filtering by
     * {@code teamId}/{@code type} and access is applied in the service layer.
     *
     * @param tenantId the owning tenant's id
     * @return the tenant's events, ordered by {@code startDate} descending
     */
    List<CapacityEvent> findAllByTenantIdOrderByStartDateDesc(Long tenantId);

    /**
     * Lists the direct children of a PI Planning event, ordered by {@code startDate}.
     *
     * @param parentEventId the parent event's id
     * @return the children events, ordered by {@code startDate} ascending
     */
    List<CapacityEvent> findAllByParentEventIdOrderByStartDateAsc(UUID parentEventId);

    /**
     * Counts the direct children of an event — used to refuse deletion (409) while children
     * remain (US11.1.1).
     *
     * @param parentEventId the parent event's id
     * @return the number of direct children
     */
    long countByParentEventId(UUID parentEventId);

    /**
     * Lists the most recent {@code SPRINT} events of a team that have {@code completedPoints}
     * set, most recent {@code endDate} first, for velocity history/average (US11.4.1).
     *
     * @param teamId the owning team's id
     * @return the team's completed sprints, ordered by {@code endDate} descending
     */
    List<CapacityEvent> findAllByTeamIdAndTypeAndCompletedPointsIsNotNullOrderByEndDateDesc(
            Long teamId, CapacityEventType type);
}
