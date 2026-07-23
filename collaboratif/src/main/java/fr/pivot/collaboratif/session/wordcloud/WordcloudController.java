package fr.pivot.collaboratif.session.wordcloud;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionAccessService;
import fr.pivot.collaboratif.session.SessionCallerResolver;
import fr.pivot.collaboratif.session.wordcloud.dto.SubmitWordRequest;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for the WORDCLOUD activity (US19.3.3).
 *
 * <p>{@link #submit} identifies the acting participant from either a bearer token or an {@code
 * X-Guest-Token} header via {@link SessionCallerResolver}, mirroring {@link
 * fr.pivot.collaboratif.session.poll.PollController#vote}. {@link #remove} is a facilitator
 * moderation action and uses the standard owner-or-{@code ROLE_ADMIN} resolution.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/sessions/{id}/wordcloud/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions/{id}/wordcloud")
public class WordcloudController {

    private final WordcloudActivityService wordcloudActivityService;
    private final SessionAccessService accessService;
    private final SessionCallerResolver callerResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param wordcloudActivityService the WORDCLOUD business logic service
     * @param accessService            resolves the session with owner-or-admin enforcement
     * @param callerResolver           resolves the acting participant for the submit endpoint
     */
    public WordcloudController(
            final WordcloudActivityService wordcloudActivityService,
            final SessionAccessService accessService,
            final SessionCallerResolver callerResolver) {
        this.wordcloudActivityService = wordcloudActivityService;
        this.accessService = accessService;
        this.callerResolver = callerResolver;
    }

    /**
     * Submits a word (US19.3.3).
     *
     * @param id          the session's UUID
     * @param request     the word submission request
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @PostMapping("/words")
    public void submit(
            @PathVariable final UUID id,
            @Valid @RequestBody final SubmitWordRequest request,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        wordcloudActivityService.submitWord(session, participantId, request.word());
    }

    /**
     * Removes a word entirely from the cloud (US19.3.3, facilitator moderation) — owner or
     * {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param word      the word to remove
     * @param principal the resolved caller identity
     */
    @DeleteMapping("/words/{word}")
    public void remove(
            @PathVariable final UUID id,
            @PathVariable final String word,
            final CollaboratifRequestPrincipal principal) {
        accessService.resolveSessionForOwnerOrAdmin(id, principal);
        wordcloudActivityService.removeWord(id, word);
    }
}
