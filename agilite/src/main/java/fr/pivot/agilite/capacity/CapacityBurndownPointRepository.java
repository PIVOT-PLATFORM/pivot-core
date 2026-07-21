package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link CapacityBurndownPoint} (E11 — capacity planning), schema
 * {@code agilite}.
 *
 * <p>Not directly tenant-scoped — same rationale as {@link CapacityEventMemberRepository}.
 */
public interface CapacityBurndownPointRepository extends JpaRepository<CapacityBurndownPoint, UUID> {

    /**
     * Finds every burndown point of an event, oldest first.
     *
     * @param eventId the owning event's identifier
     * @return the event's burndown points, ordered by {@link CapacityBurndownPoint#getDate()}
     */
    List<CapacityBurndownPoint> findByEventIdOrderByDateAsc(UUID eventId);
}
