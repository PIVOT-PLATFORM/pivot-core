package fr.pivot.agilite.capacity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link CapacityAbsence} entities (US11.2.2).
 */
public interface CapacityAbsenceRepository extends JpaRepository<CapacityAbsence, UUID> {

    /**
     * Finds an absence by id, scoped to the expected event member.
     *
     * @param id            the absence UUID
     * @param eventMemberId the expected owning event member's UUID
     * @return the matching absence, or empty if not found or owned by another member
     */
    Optional<CapacityAbsence> findByIdAndEventMemberId(UUID id, UUID eventMemberId);

    /**
     * Lists every absence for a single event member, ordered by {@code dateDebut}.
     *
     * @param eventMemberId the owning event member's UUID
     * @return the member's absences, ordered by {@code dateDebut} ascending
     */
    List<CapacityAbsence> findAllByEventMemberIdOrderByDateDebutAsc(UUID eventMemberId);

    /**
     * Lists every absence for a batch of event members in one query — used when building the
     * member roster response (US11.2.1) to avoid N+1 queries.
     *
     * @param eventMemberIds the owning event members' UUIDs
     * @return the matching absences, in no particular order (grouped client-side by caller)
     */
    List<CapacityAbsence> findAllByEventMemberIdIn(List<UUID> eventMemberIds);
}
