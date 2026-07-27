package fr.pivot.collaboratif.session.postitrush;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionAccessService;
import fr.pivot.collaboratif.session.SessionCallerResolver;
import fr.pivot.collaboratif.session.postitrush.dto.ClickPostitRequest;
import fr.pivot.collaboratif.session.postitrush.dto.ClickPostitResponse;
import fr.pivot.collaboratif.session.postitrush.dto.PostitRushResultsDto;
import fr.pivot.collaboratif.session.postitrush.dto.PostitRushStateDto;
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
 * REST controller for the POST-IT RUSH activity (US47.2.1).
 *
 * <p>{@link #start} is a facilitator action (owner-or-{@code ROLE_ADMIN}) — it triggers the
 * server-authoritative round clock. {@link #click}, {@link #state} and {@link #results} identify
 * the acting participant from a bearer token or {@code X-Guest-Token} via
 * {@link SessionCallerResolver}, the same dual-credential shape as the other activities.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/sessions/{id}/postit-rush/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions/{id}/postit-rush")
public class PostitRushController {

    private final PostitRushActivityService activityService;
    private final PostitRushClickRateLimitService rateLimitService;
    private final SessionAccessService accessService;
    private final SessionCallerResolver callerResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param activityService  the POST-IT RUSH business logic service
     * @param rateLimitService the Redis-backed per-participant click rate limiter
     * @param accessService    resolves the session with owner-or-admin enforcement
     * @param callerResolver   resolves the acting participant for participant endpoints
     */
    public PostitRushController(
            final PostitRushActivityService activityService,
            final PostitRushClickRateLimitService rateLimitService,
            final SessionAccessService accessService,
            final SessionCallerResolver callerResolver) {
        this.activityService = activityService;
        this.rateLimitService = rateLimitService;
        this.accessService = accessService;
        this.callerResolver = callerResolver;
    }

    /**
     * Starts a new round (US47.2.1) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/start")
    public void start(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        Session session = accessService.resolveSessionForOwnerOrAdmin(id, principal);
        activityService.startRound(session);
    }

    /**
     * Records the caller's click on a live post-it (US47.2.1) — server-authoritative scoring; the
     * request carries only the {@code postitId}, never a score.
     *
     * @param id          the session's UUID
     * @param request     the click request
     * @param httpRequest the raw HTTP request, used to resolve the acting participant and rate-limit
     * @return the clicking participant's updated score/combo state
     */
    @PostMapping("/click")
    public ClickPostitResponse click(
            @PathVariable final UUID id,
            @Valid @RequestBody final ClickPostitRequest request,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        rateLimitService.checkAndIncrement(participantId);
        Session session = accessService.loadById(id);
        return activityService.click(session, participantId, request);
    }

    /**
     * Returns the caller's reconnect snapshot (US47.2.1) — remaining time, currently-live
     * post-its, and the caller's own score/combo.
     *
     * @param id          the session's UUID
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     * @return the reconnect snapshot
     */
    @GetMapping("/state")
    public PostitRushStateDto state(@PathVariable final UUID id, final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        return activityService.getState(session, participantId);
    }

    /**
     * Returns the final standings of the most recently played round (US47.2.1),
     * participant-accessible.
     *
     * @param id          the session's UUID
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     * @return the final results
     */
    @GetMapping("/results")
    public PostitRushResultsDto results(@PathVariable final UUID id, final HttpServletRequest httpRequest) {
        callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        return activityService.getResults(session);
    }
}
