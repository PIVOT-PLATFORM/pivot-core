package fr.pivot.collaboratif.meetops.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link ProposedSlot} persistence (US12.4.1).
 */
public interface ProposedSlotRepository extends JpaRepository<ProposedSlot, UUID> {

    /**
     * Returns every candidate for a meeting, ordered by rank (recommended first).
     *
     * @param meetingId the meeting id
     * @return the ranked candidates
     */
    List<ProposedSlot> findByMeetingIdOrderByRank(UUID meetingId);

    /**
     * Finds a specific candidate, scoped to its owning meeting — used to validate that a {@code
     * slotId} supplied on confirm/adjust genuinely belongs to that meeting's own candidates
     * (US12.4.1 "Error — validation d'un créneau invalide").
     *
     * @param id        the candidate slot id
     * @param meetingId the meeting it must belong to
     * @return the matching candidate, if any
     */
    Optional<ProposedSlot> findByIdAndMeetingId(UUID id, UUID meetingId);

    /**
     * Deletes every existing candidate of a meeting — used before regenerating them on a {@code
     * window.updated} recompute (US12.4.1).
     *
     * @param meetingId the meeting id
     */
    @Modifying
    @Query("DELETE FROM ProposedSlot s WHERE s.meeting.id = :meetingId")
    void deleteByMeetingId(@Param("meetingId") UUID meetingId);
}
