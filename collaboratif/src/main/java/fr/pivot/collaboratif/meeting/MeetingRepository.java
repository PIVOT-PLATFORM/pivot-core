package fr.pivot.collaboratif.meeting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
