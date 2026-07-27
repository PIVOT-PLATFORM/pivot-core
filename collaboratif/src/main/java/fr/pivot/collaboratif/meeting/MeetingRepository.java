package fr.pivot.collaboratif.meeting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
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
     * Finds the meeting previously upserted for a given {@code (tenant_id, event_ref)} pair
     * (US12.4.1 idempotence AC) — backed by the partial unique index {@code
     * uq_meeting_event_ref}.
     *
     * @param tenantId the owning tenant
     * @param eventRef the upstream roadmap event correlation id
     * @return the matching meeting, if one was already created for this event
     */
    Optional<Meeting> findByTenantIdAndEventRef(Long tenantId, String eventRef);

    /**
     * Finds a meeting scoped to a tenant — used by every booking endpoint/consumer so a
     * cross-tenant id never resolves (US12.4.1 tenant-isolation AC, anti-IDOR).
     *
     * @param id       the meeting id
     * @param tenantId the caller's tenant
     * @return the matching meeting, if it belongs to that tenant
     */
    Optional<Meeting> findByIdAndTenantId(UUID id, Long tenantId);
}
