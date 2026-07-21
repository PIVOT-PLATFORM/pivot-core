package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link CapacityAbsence} (E11 — capacity planning), schema {@code
 * agilite}.
 *
 * <p>Not directly tenant-scoped — same rationale as {@link CapacityEventMemberRepository}.
 */
public interface CapacityAbsenceRepository extends JpaRepository<CapacityAbsence, UUID> {

    /**
     * Finds every absence of an event member.
     *
     * @param eventMemberId the owning event member's identifier
     * @return the member's absences, in no particular order
     */
    List<CapacityAbsence> findByEventMemberId(UUID eventMemberId);

    /**
     * Finds every absence of every member listed, in a single query — used by the (future)
     * calculator/service layer to load an entire event's absences without one query per member.
     *
     * @param eventMemberIds the owning event members' identifiers
     * @return the matching absences, in no particular order
     */
    List<CapacityAbsence> findByEventMemberIdIn(List<UUID> eventMemberIds);
}
