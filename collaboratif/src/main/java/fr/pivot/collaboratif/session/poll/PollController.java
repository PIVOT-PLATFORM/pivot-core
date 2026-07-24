package fr.pivot.collaboratif.session.poll;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionAccessService;
import fr.pivot.collaboratif.session.SessionCallerResolver;
import fr.pivot.collaboratif.session.poll.dto.PollVoteRequest;
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
 * REST controller for the POLL activity (US19.3.2).
 *
 * <p>{@link #vote} identifies the acting participant from either a bearer token or an {@code
 * X-Guest-Token} header via {@link SessionCallerResolver} — POLL sessions are joined by both
 * authenticated and anonymous participants (US19.2.1). {@link #hideResults}/{@link #showResults}
 * are facilitator actions and use the standard {@link CollaboratifRequestPrincipal} resolution
 * (owner-or-{@code ROLE_ADMIN}, mirroring US19.1.2's lifecycle authorization).
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/sessions/{id}/poll/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions/{id}/poll")
public class PollController {

    private final PollActivityService pollActivityService;
    private final SessionAccessService accessService;
    private final SessionCallerResolver callerResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param pollActivityService the POLL business logic service
     * @param accessService       resolves the session with owner-or-admin enforcement
     * @param callerResolver      resolves the acting participant for the vote endpoint
     */
    public PollController(
            final PollActivityService pollActivityService,
            final SessionAccessService accessService,
            final SessionCallerResolver callerResolver) {
        this.pollActivityService = pollActivityService;
        this.accessService = accessService;
        this.callerResolver = callerResolver;
    }

    /**
     * Casts or replaces the caller's vote (US19.3.2).
     *
     * @param id          the session's UUID
     * @param request     the vote request
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @PostMapping("/vote")
    public void vote(
            @PathVariable final UUID id,
            @Valid @RequestBody final PollVoteRequest request,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        pollActivityService.vote(session, participantId, request.optionIds());
    }

    /**
     * Hides poll results from participants (US19.3.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/hide-results")
    public void hideResults(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        accessService.resolveSessionForOwnerOrAdmin(id, principal);
        pollActivityService.hideResults(id);
    }

    /**
     * Shows poll results to participants again (US19.3.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/show-results")
    public void showResults(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        accessService.resolveSessionForOwnerOrAdmin(id, principal);
        pollActivityService.showResults(id);
    }
}
