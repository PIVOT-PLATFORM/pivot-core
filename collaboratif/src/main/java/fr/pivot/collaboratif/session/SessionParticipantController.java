package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.session.dto.GuestHeartbeatRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionResponse;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for the unified session join endpoint and guest heartbeat (US19.2.1).
 *
 * <p>Unlike every other collaboratif endpoint, {@link #join} deliberately does <strong>not</strong>
 * require a {@code CollaboratifRequestPrincipal} argument (which would force a 401 for callers
 * with no bearer token) — it inspects the {@code Authorization} header itself, optionally, to
 * support both authenticated and anonymous {@code ROLE_GUEST} callers in a single endpoint.
 *
 * <p>The full path (including the application context) is {@code /api/collaboratif/sessions/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions")
public class SessionParticipantController {

    private final SessionParticipantService participantService;
    private final SessionCallerResolver callerResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param participantService the join/heartbeat business logic service
     * @param callerResolver     resolves an optional bearer-token principal
     */
    public SessionParticipantController(
            final SessionParticipantService participantService, final SessionCallerResolver callerResolver) {
        this.participantService = participantService;
        this.callerResolver = callerResolver;
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
}
