package fr.pivot.collaboratif.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Session}.
 */
public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * Checks whether a join code is currently in use by a non-{@link SessionStatus#COMPLETED}
     * session of the given tenant.
     *
     * @param tenantId the tenant to scope the check to
     * @param joinCode the candidate join code
     * @return {@code true} if the code collides with an active session
     */
    boolean existsByTenantIdAndJoinCodeAndStatusNot(Long tenantId, String joinCode, SessionStatus status);

    /**
     * Resolves a joinable session by its code within a tenant — excludes
     * {@link SessionStatus#COMPLETED} sessions (US19.2.1: a completed session is treated as
     * non-existent for joining purposes).
     *
     * @param tenantId the tenant to scope the lookup to
     * @param joinCode the join code
     * @return the session if found and not completed
     */
    Optional<Session> findByTenantIdAndJoinCodeAndStatusNot(Long tenantId, String joinCode, SessionStatus status);

    /**
     * Lists sessions of a tenant, most recently created first.
     *
     * @param tenantId the tenant to scope the listing to
     * @return sessions, most recently created first
     */
    List<Session> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    /**
     * Resolves a joinable session by its code, <strong>not</strong> scoped to a tenant — used by
     * the unified {@code POST /sessions/join} endpoint (US19.2.1), which must also serve
     * anonymous callers who carry no tenant context at all.
     *
     * <p>Join codes are only guaranteed unique <em>within</em> a tenant among non-{@link
     * SessionStatus#COMPLETED} sessions ({@code uq_session_join_code_active}); a cross-tenant
     * collision is possible in principle (32^6 code space vs. realistic session volume makes it
     * vanishingly unlikely in practice) but not structurally prevented, so this resolves to a
     * single arbitrary match rather than failing — a documented, accepted simplification.
     *
     * @param joinCode the join code
     * @param status   excluded status ({@link SessionStatus#COMPLETED})
     * @return a matching session, if any
     */
    Optional<Session> findFirstByJoinCodeAndStatusNot(String joinCode, SessionStatus status);
}
