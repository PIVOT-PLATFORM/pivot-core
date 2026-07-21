package fr.pivot.agilite.standup;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link StandupSession} entities (US10.1.1).
 */
public interface StandupSessionRepository extends JpaRepository<StandupSession, UUID> {

    /**
     * Finds a session by its identifier, verifying it belongs to the given tenant.
     *
     * <p>Returns {@link Optional#empty()} if the session does not exist or belongs to a different
     * tenant, preventing cross-tenant information disclosure.
     *
     * @param id       the session UUID
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return an {@link Optional} containing the session, or empty if not found
     */
    Optional<StandupSession> findByIdAndTenantId(UUID id, Long tenantId);

    /**
     * Finds all sessions belonging to any of the given teams, scoped to the given tenant, most
     * recently created first.
     *
     * @param teamIds  the teams to include
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @return the matching sessions, {@code createdAt} descending
     */
    List<StandupSession> findAllByTeamIdInAndTenantIdOrderByCreatedAtDesc(List<Long> teamIds, Long tenantId);

    /**
     * Finds all sessions belonging to any of the given teams and matching the given status,
     * scoped to the given tenant, most recently created first.
     *
     * @param teamIds  the teams to include
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @param status   the required session status
     * @return the matching sessions, {@code createdAt} descending
     */
    List<StandupSession> findAllByTeamIdInAndTenantIdAndStatusOrderByCreatedAtDesc(
            List<Long> teamIds, Long tenantId, StandupSessionStatus status);

    /**
     * Finds every {@link StandupSessionStatus#DONE} session of a team, started within the given
     * window, scoped to the given tenant, most recently started first (US10.3.1 stats).
     *
     * @param teamId   the team's {@code public.teams.id}
     * @param tenantId the expected tenant's {@code public.tenants.id}
     * @param status   always {@link StandupSessionStatus#DONE} at the call site — kept as a
     *                 parameter rather than hardcoded in the query for testability/consistency
     *                 with the other derived queries on this repository
     * @param from     inclusive lower bound on {@code startedAt}
     * @param to       inclusive upper bound on {@code startedAt} (Spring Data {@code Between}
     *                 compiles to SQL {@code BETWEEN}, inclusive on both ends — callers passing a
     *                 calendar-day {@code to} bound must therefore pass the end-of-day instant,
     *                 not midnight, to include that whole day)
     * @return the matching sessions, {@code startedAt} descending
     */
    List<StandupSession> findAllByTeamIdAndTenantIdAndStatusAndStartedAtBetweenOrderByStartedAtDesc(
            Long teamId, Long tenantId, StandupSessionStatus status, Instant from, Instant to);
}
