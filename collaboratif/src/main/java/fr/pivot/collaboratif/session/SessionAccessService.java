package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.core.team.TeamMemberRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resolves a {@link Session} for a caller, enforcing tenant isolation and the two distinct
 * authorization levels required by E19: general visibility (US19.1.1 {@code GET}) versus
 * lifecycle/facilitation authority (US19.1.2 owner-or-{@code ROLE_ADMIN}).
 *
 * <p>Every denial — wrong tenant, unknown session, or insufficient authorization — surfaces as
 * the identical {@link SessionNotFoundException} (404), never a distinguishable 403: this is the
 * anti-enumeration convention used across every collaboratif module.
 */
@Service
public class SessionAccessService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final SessionRepository sessionRepository;
    private final TeamMemberRepository teamMemberRepository;

    /**
     * Creates the service with its required dependencies.
     *
     * @param sessionRepository    repository for session lookups
     * @param teamMemberRepository repository used to check team membership (visibility only)
     */
    public SessionAccessService(
            final SessionRepository sessionRepository, final TeamMemberRepository teamMemberRepository) {
        this.sessionRepository = sessionRepository;
        this.teamMemberRepository = teamMemberRepository;
    }

    /**
     * Resolves a session for general access (US19.1.1) — the creator, or any member of the
     * session's team when it has one.
     *
     * @param sessionId the session's UUID
     * @param principal the caller
     * @return the session
     * @throws SessionNotFoundException if the session does not exist, belongs to another tenant,
     *                                   or the caller has no visibility into it
     */
    public Session resolveSessionForCaller(final UUID sessionId, final CollaboratifRequestPrincipal principal) {
        Session session = loadInTenant(sessionId, principal.tenantId());
        if (!isVisibleToCaller(session, principal)) {
            throw new SessionNotFoundException();
        }
        return session;
    }

    /**
     * Returns whether the caller may see the given session — the creator, or any member of the
     * session's team when it has one. Exposed for list-style queries (US19.1.1 {@code GET
     * /sessions}) that filter an already-tenant-scoped collection rather than resolving a single
     * session by id.
     *
     * @param session   the session to check (must already be tenant-scoped to the caller)
     * @param principal the caller
     * @return {@code true} if the caller has visibility into the session
     */
    public boolean isVisibleToCaller(final Session session, final CollaboratifRequestPrincipal principal) {
        boolean isCreator = session.getCreatedBy().equals(principal.userId());
        boolean isTeamMember = session.getTeamId() != null
                && teamMemberRepository.findByTeamIdAndUserId(session.getTeamId(), principal.userId()).isPresent();
        return isCreator || isTeamMember;
    }

    /**
     * Resolves a session for lifecycle/facilitation actions (US19.1.2) — the creator or a
     * platform {@code ROLE_ADMIN} only. {@code teamId} never grants this level of authority.
     *
     * @param sessionId the session's UUID
     * @param principal the caller
     * @return the session
     * @throws SessionNotFoundException if the session does not exist, belongs to another tenant,
     *                                   or the caller is neither the owner nor an admin
     */
    public Session resolveSessionForOwnerOrAdmin(
            final UUID sessionId, final CollaboratifRequestPrincipal principal) {
        Session session = loadInTenant(sessionId, principal.tenantId());
        boolean isOwner = session.getCreatedBy().equals(principal.userId());
        boolean isAdmin = ROLE_ADMIN.equals(principal.role());
        if (!isOwner && !isAdmin) {
            throw new SessionNotFoundException();
        }
        return session;
    }

    /**
     * Loads a session by id with no tenant/authorization check — used only by activity write
     * endpoints (POLL vote, WORDCLOUD submission) where the real authorization gate is already
     * {@link SessionCallerResolver} having resolved a {@link Participant} scoped to this exact
     * session (bearer-token participants only ever resolve within their own tenant's sessions by
     * construction; guest tokens are sealed per-session by construction) — re-deriving a tenant
     * here would be redundant, not additional safety.
     *
     * @param sessionId the session's UUID
     * @return the session
     * @throws SessionNotFoundException if the session does not exist
     */
    public Session loadById(final UUID sessionId) {
        return sessionRepository.findById(sessionId).orElseThrow(SessionNotFoundException::new);
    }

    private Session loadInTenant(final UUID sessionId, final Long tenantId) {
        Session session = sessionRepository.findById(sessionId).orElseThrow(SessionNotFoundException::new);
        if (!session.getTenantId().equals(tenantId)) {
            throw new SessionNotFoundException();
        }
        return session;
    }
}
