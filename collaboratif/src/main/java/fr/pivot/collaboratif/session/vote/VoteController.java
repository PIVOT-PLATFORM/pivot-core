package fr.pivot.collaboratif.session.vote;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionAccessService;
import fr.pivot.collaboratif.session.SessionCallerResolver;
import fr.pivot.collaboratif.session.vote.dto.SubmitBallotRequest;
import fr.pivot.collaboratif.session.vote.dto.VoteResultsDto;
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
 * REST controller for the VOTE activity (US19.3.6).
 *
 * <p>{@link #ballot} and {@link #results} identify the acting participant from either a bearer
 * token or an {@code X-Guest-Token} header via {@link SessionCallerResolver} — the same
 * dual-credential shape as POLL/WORDCLOUD/Q&A/BRAINSTORM. {@link #close} is a facilitator action
 * (owner-or-{@code ROLE_ADMIN}). Results stay tally-less until {@link #close} has run.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/sessions/{id}/vote/...}.
 *
 * <p>Explicit bean name {@code sessionVoteController} to avoid a bean-name collision with the
 * whiteboard module's own {@code fr.pivot.collaboratif.whiteboard.vote.VoteController} (both
 * decapitalize to the default {@code voteController}).
 */
@RestController("sessionVoteController")
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions/{id}/vote")
public class VoteController {

    private final VoteActivityService voteActivityService;
    private final SessionAccessService accessService;
    private final SessionCallerResolver callerResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param voteActivityService the VOTE business logic service
     * @param accessService       resolves the session with owner-or-admin enforcement
     * @param callerResolver      resolves the acting participant for participant endpoints
     */
    public VoteController(
            final VoteActivityService voteActivityService,
            final SessionAccessService accessService,
            final SessionCallerResolver callerResolver) {
        this.voteActivityService = voteActivityService;
        this.accessService = accessService;
        this.callerResolver = callerResolver;
    }

    /**
     * Casts the caller's ballot (US19.3.6) — one per participant, a second is a 409.
     *
     * @param id          the session's UUID
     * @param request     the ballot (the relevant field depends on the vote type)
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @PostMapping("/ballot")
    public void ballot(
            @PathVariable final UUID id,
            @Valid @RequestBody final SubmitBallotRequest request,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        voteActivityService.submitBallot(session, participantId, request);
    }

    /**
     * Closes the vote and reveals the results (US19.3.6) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/close")
    public void close(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        Session session = accessService.resolveSessionForOwnerOrAdmin(id, principal);
        voteActivityService.close(session);
    }

    /**
     * Returns the current results (US19.3.6) — participant-accessible; tallies are withheld until
     * the facilitator closes the vote.
     *
     * @param id          the session's UUID
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     * @return the results (open-state, no tallies, if not yet closed)
     */
    @GetMapping("/results")
    public VoteResultsDto results(@PathVariable final UUID id, final HttpServletRequest httpRequest) {
        callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        return voteActivityService.getResults(session);
    }
}
