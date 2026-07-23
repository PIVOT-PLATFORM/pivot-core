package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.session.dto.GuestHeartbeatRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionResponse;
import fr.pivot.collaboratif.session.dto.ParticipantSessionResponse;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for the unified session join endpoint, guest heartbeat (US19.2.1), and the
 * participant-safe session-state read (US19.2.2).
 *
 * <p>Unlike every other collaboratif endpoint, {@link #join} deliberately does <strong>not</strong>
 * require a {@code CollaboratifRequestPrincipal} argument (which would force a 401 for callers
 * with no bearer token) — it inspects the {@code Authorization} header itself, optionally, to
 * support both authenticated and anonymous {@code ROLE_GUEST} callers in a single endpoint.
 *
 * <p>{@link #getState} follows the same dual-credential shape as the POLL vote / WORDCLOUD submit
 * endpoints ({@link SessionCallerResolver#resolveParticipantId}): it exists because {@code
 * ModuleSessionController#getById} requires a full {@code CollaboratifRequestPrincipal} (bearer
 * token only), which an anonymous {@code ROLE_GUEST} participant never has by construction
 * (US19.2.1) — yet the participant view must reload session state on join and on every STOMP
 * reconnect (US19.2.2). It is intentionally a distinct path from {@code GET /sessions/{id}}
 * (Spring would otherwise see two ambiguous mappings on the identical path/method) and returns a
 * deliberately narrower {@link ParticipantSessionResponse}, not the facilitator-oriented {@code
 * SessionResponse} — see that record's Javadoc for the exact fields withheld and why.
 *
 * <p>The full path (including the application context) is {@code /api/collaboratif/sessions/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions")
public class SessionParticipantController {

    private final SessionParticipantService participantService;
    private final SessionCallerResolver callerResolver;
    private final SessionAccessService accessService;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param participantService the join/heartbeat/session-state business logic service
     * @param callerResolver     resolves an optional bearer-token principal, or the acting
     *                           participant for a joined-caller-only endpoint
     * @param accessService      resolves a session by id with no further authorization (the
     *                           participant/guest gate is {@code callerResolver} itself)
     */
    public SessionParticipantController(
            final SessionParticipantService participantService,
            final SessionCallerResolver callerResolver,
            final SessionAccessService accessService) {
        this.participantService = participantService;
        this.callerResolver = callerResolver;
        this.accessService = accessService;
    }

    /**
     * Joins a session by its code — authenticated or anonymous (US19.2.1).
     *
     * @param request     the join request
     * @param httpRequest the raw HTTP request, inspected for an optional bearer token
     * @return the created participant's id, guest token (anonymous only), and WS topic
     */
    @PostMapping("/join")
    public JoinSessionResponse join(
            @Valid @RequestBody final JoinSessionRequest request, final HttpServletRequest httpRequest) {
        return participantService.join(request, callerResolver.resolveOptionalPrincipal(httpRequest));
    }

    /**
     * Refreshes a guest participant's rolling TTL (US19.2.1, guest only).
     *
     * @param id            the session's UUID
     * @param participantId the participant's UUID
     * @param request       the heartbeat request carrying the guest token
     */
    @PostMapping("/{id}/participants/{participantId}/heartbeat")
    public void heartbeat(
            @PathVariable final UUID id,
            @PathVariable final UUID participantId,
            @Valid @RequestBody final GuestHeartbeatRequest request) {
        participantService.heartbeat(id, participantId, request);
    }

    /**
     * Returns the participant-safe view of a session's current state (US19.2.2) — reachable by
     * any caller who has already joined this exact session, authenticated or anonymous guest
     * alike, identified via {@link SessionCallerResolver#resolveParticipantId}.
     *
     * @param id          the session's UUID
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     * @return the participant-safe session view
     * @throws fr.pivot.collaboratif.exception.SessionGuestExpiredException if no bearer token and
     *                                                                      no valid, correctly
     *                                                                      scoped guest token is
     *                                                                      present (401)
     * @throws fr.pivot.collaboratif.exception.SessionNotFoundException    if an authenticated
     *                                                                      caller never joined
     *                                                                      this session, or the
     *                                                                      session itself does
     *                                                                      not exist (404)
     */
    @GetMapping("/{id}/state")
    public ParticipantSessionResponse getState(
            @PathVariable final UUID id, final HttpServletRequest httpRequest) {
        callerResolver.resolveParticipantId(httpRequest, id);
        return participantService.getForParticipant(accessService.loadById(id));
    }
}
