package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.dto.CreateSessionRequest;
import fr.pivot.collaboratif.session.dto.SessionResponse;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for Module Session creation, listing, and lifecycle transitions (US19.1.1,
 * US19.1.2).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link CollaboratifRequestPrincipal} by {@code CollaboratifRequestPrincipalResolver} (EN08.3).
 * A session that is unknown, cross-tenant, or that the caller has no authority over resolves to
 * HTTP 404 (never 403).
 *
 * <p>The full path (including the application context) is {@code /api/collaboratif/sessions/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions")
public class SessionController {

    private final SessionService sessionService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param sessionService the session business logic service
     */
    public SessionController(final SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Creates a new session in {@link SessionStatus#DRAFT} (US19.1.1).
     *
     * @param request   the creation request
     * @param principal the resolved caller identity
     * @return HTTP 201 with the created session
     */
    @PostMapping
    public ResponseEntity<SessionResponse> create(
            @Valid @RequestBody final CreateSessionRequest request, final CollaboratifRequestPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.create(request, principal));
    }

    /**
     * Lists sessions visible to the caller, optionally filtered (US19.1.1).
     *
     * @param teamId    optional team filter
     * @param status    optional status filter
     * @param principal the resolved caller identity
     * @return the visible sessions, most recently created first
     */
    @GetMapping
    public List<SessionResponse> list(
            @RequestParam(required = false) final Long teamId,
            @RequestParam(required = false) final SessionStatus status,
            final CollaboratifRequestPrincipal principal) {
        return sessionService.list(principal, teamId, status);
    }

    /**
     * Retrieves a single session (US19.1.1).
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     * @return the session
     */
    @GetMapping("/{id}")
    public SessionResponse getById(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        return sessionService.getById(id, principal);
    }

    /**
     * Starts a DRAFT session (US19.1.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/{id}/start")
    public void start(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        sessionService.start(id, principal);
    }

    /**
     * Pauses a LIVE session (US19.1.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/{id}/pause")
    public void pause(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        sessionService.pause(id, principal);
    }

    /**
     * Resumes a PAUSED session (US19.1.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/{id}/resume")
    public void resume(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        sessionService.resume(id, principal);
    }

    /**
     * Ends a LIVE or PAUSED session (US19.1.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/{id}/end")
    public void end(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        sessionService.end(id, principal);
    }
}
