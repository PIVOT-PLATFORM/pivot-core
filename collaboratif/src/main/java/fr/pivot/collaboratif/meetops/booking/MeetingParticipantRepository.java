package fr.pivot.collaboratif.meetops.booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link MeetingParticipant} persistence (US12.4.1).
 */
public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, UUID> {

    /**
     * Returns every participant record of a meeting.
     *
     * @param meetingId the meeting id
     * @return the participants (possibly empty)
     */
    List<MeetingParticipant> findByMeetingId(UUID meetingId);

    /**
     * Resolves an e-mail to a {@code public.users.id} within a tenant — best-effort organizer/
     * participant identity resolution for a booking-flow meeting created from an event whose
     * {@code participants[]} carries raw e-mails, not platform user ids (US12.4.1). Mirrors
     * {@code MeetingRepository#teamBelongsToTenant}'s native-query anti-enumeration pattern:
     * scoped to a single tenant, so an e-mail registered in another tenant never resolves here.
     *
     * @param tenantId the tenant to resolve within
     * @param email    the e-mail to resolve
     * @return the matching {@code public.users.id}, or {@code null} if none
     */
    @Query(value = "SELECT id FROM public.users WHERE tenant_id = :tenantId AND email = :email LIMIT 1",
            nativeQuery = true)
    Long resolveUserIdByEmail(@Param("tenantId") Long tenantId, @Param("email") String email);

    /**
     * Returns {@code true} when {@code userId} is a registered participant (resolved) of the
     * given meeting — used by both the confirm/adjust REST authorization and the STOMP room
     * SUBSCRIBE authorization ("organisateur/participant").
     *
     * @param meetingId the meeting id
     * @param userId    the caller's {@code public.users.id}
     * @return {@code true} if a participant record resolves to this user
     */
    boolean existsByMeetingIdAndParticipantUserId(UUID meetingId, Long userId);

    /**
     * Deletes every existing participant record of a meeting — used before re-inserting them on
     * a {@code window.updated} recompute (US12.4.1).
     *
     * @param meetingId the meeting id
     */
    @Modifying
    @Query("DELETE FROM MeetingParticipant p WHERE p.meeting.id = :meetingId")
    void deleteByMeetingId(@Param("meetingId") UUID meetingId);
}
