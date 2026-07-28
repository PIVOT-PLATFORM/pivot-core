package fr.pivot.collaboratif.meeting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data repository for {@link Meeting} persistence (US12.1.1).
 */
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    /**
     * Checks whether {@code teamId} exists and belongs to {@code tenantId} — used to validate a
     * creation request's optional {@code teamId} without leaking the existence of a team from
     * another tenant (AC7, anti-enumeration; same pattern as {@code
     * SessionKpiRepository#teamBelongsToTenant}).
     *
     * @param teamId   the {@code public.teams.id} to check
     * @param tenantId the caller's tenant, from {@code TenantContext}
     * @return {@code true} if the team exists within that tenant
     */
    @Query(value = "SELECT EXISTS(SELECT 1 FROM public.teams WHERE id = :teamId AND tenant_id = :tenantId)",
            nativeQuery = true)
    boolean teamBelongsToTenant(@Param("teamId") Long teamId, @Param("tenantId") Long tenantId);

    /**
     * Returns the ids of every meeting currently {@link MeetingStatus#IN_PROGRESS} — polled every
     * second by {@code MeetingTimerScheduler} (US12.2.1 AC-02/AC-04/AC-05). Deliberately a scalar
     * id projection, not the full entity graph: each id is then re-loaded and ticked inside its
     * own short-lived transaction (mirrors {@code fr.pivot.agilite.standup.StandupTimerScheduler}'s
     * identical scalar-projection pattern), so one meeting's tick can never hold a transaction —
     * or a lazy {@link Meeting#getAgendaItems()} fetch — open across every other meeting's tick.
     *
     * @param status the status to filter by (always {@link MeetingStatus#IN_PROGRESS} in practice)
     * @return the matching meeting ids, in no particular order
     */
    @Query("select m.id from Meeting m where m.status = :status")
    List<UUID> findIdsByStatus(@Param("status") MeetingStatus status);
}
