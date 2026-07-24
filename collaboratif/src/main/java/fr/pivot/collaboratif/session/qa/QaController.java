package fr.pivot.collaboratif.session.qa;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionAccessService;
import fr.pivot.collaboratif.session.SessionCallerResolver;
import fr.pivot.collaboratif.session.qa.dto.QaQuestionDto;
import fr.pivot.collaboratif.session.qa.dto.SubmitQuestionRequest;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the Q&amp;A activity (US19.3.5).
 *
 * <p>{@link #submit}, {@link #upvote} and {@link #list} identify the acting participant from
 * either a bearer token or an {@code X-Guest-Token} header via {@link SessionCallerResolver},
 * mirroring {@link fr.pivot.collaboratif.session.poll.PollController#vote} — Q&amp;A sessions are
 * joined by both authenticated and anonymous participants (US19.2.1). {@link #answered} is a
 * facilitator action and uses the standard owner-or-{@code ROLE_ADMIN} resolution.
 *
 * <p>{@link #list} exists because, unlike POLL/WORDCLOUD (whose live state reaches participants
 * only through WS broadcasts), a participant joining a Q&amp;A mid-session must see the questions
 * already asked — so the current list is exposed on a participant-accessible read, the same
 * dual-credential shape as {@code SessionParticipantController#getState}.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/sessions/{id}/qa/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions/{id}/qa")
public class QaController {

    private final QaActivityService qaActivityService;
    private final SessionAccessService accessService;
    private final SessionCallerResolver callerResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param qaActivityService the Q&amp;A business logic service
     * @param accessService     resolves the session with owner-or-admin enforcement
     * @param callerResolver    resolves the acting participant for participant endpoints
     */
    public QaController(
            final QaActivityService qaActivityService,
            final SessionAccessService accessService,
            final SessionCallerResolver callerResolver) {
        this.qaActivityService = qaActivityService;
        this.accessService = accessService;
        this.callerResolver = callerResolver;
    }

    /**
     * Submits a question (US19.3.5).
     *
     * @param id          the session's UUID
     * @param request     the question submission request
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @PostMapping("/questions")
    public void submit(
            @PathVariable final UUID id,
            @Valid @RequestBody final SubmitQuestionRequest request,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        qaActivityService.submitQuestion(session, participantId, request.text(), request.isAnonymous());
    }

    /**
     * Upvotes a question (US19.3.5) — one upvote per participant per question.
     *
     * @param id          the session's UUID
     * @param questionId  the question to upvote
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @PostMapping("/questions/{questionId}/upvote")
    public void upvote(
            @PathVariable final UUID id,
            @PathVariable final UUID questionId,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        qaActivityService.upvote(session, participantId, questionId);
    }

    /**
     * Marks a question as answered (US19.3.5, facilitator) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id         the session's UUID
     * @param questionId the question to mark answered
     * @param principal  the resolved caller identity
     */
    @PostMapping("/questions/{questionId}/answered")
    public void answered(
            @PathVariable final UUID id,
            @PathVariable final UUID questionId,
            final CollaboratifRequestPrincipal principal) {
        accessService.resolveSessionForOwnerOrAdmin(id, principal);
        qaActivityService.markAnswered(id, questionId);
    }

    /**
     * Lists the session's questions, most-upvoted first (US19.3.5) — participant-accessible, used
     * to hydrate the participant view on join and reconnect.
     *
     * @param id          the session's UUID
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     * @return the questions, upvotes descending then oldest first
     */
    @GetMapping("/questions")
    public List<QaQuestionDto> list(@PathVariable final UUID id, final HttpServletRequest httpRequest) {
        callerResolver.resolveParticipantId(httpRequest, id);
        return qaActivityService.getQuestions(id);
    }
}
