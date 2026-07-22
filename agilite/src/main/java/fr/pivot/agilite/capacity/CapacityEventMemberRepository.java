package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CapacityEventMember} entities (US11.2.1).
 */
public interface CapacityEventMemberRepository extends JpaRepository<CapacityEventMember, UUID> {

    /**
     * Finds a roster row by id, scoped to the expected event.
     *
     * @param id      the member row's UUID
     * @param eventId the expected owning event's UUID
     * @return the matching member, or empty if not found or owned by another event
     */
    Optional<CapacityEventMember> findByIdAndEventId(UUID id, UUID eventId);

    /**
     * Lists every roster row of an event, ordered by display name (US11.2.1 read AC).
     *
     * @param eventId the owning event's UUID
     * @return the event's members, ordered by {@code name} ascending
     */
    List<CapacityEventMember> findAllByEventIdOrderByNameAsc(UUID eventId);
}
