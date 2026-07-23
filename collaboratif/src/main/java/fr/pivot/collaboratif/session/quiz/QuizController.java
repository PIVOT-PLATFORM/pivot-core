package fr.pivot.collaboratif.session.quiz;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionAccessService;
import fr.pivot.collaboratif.session.SessionCallerResolver;
import fr.pivot.collaboratif.session.quiz.dto.QuizResultsDto;
import fr.pivot.collaboratif.session.quiz.dto.QuizStateDto;
import fr.pivot.collaboratif.session.quiz.dto.SubmitAnswerRequest;
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
 * REST controller for the QUIZ activity (US19.3.1).
 *
 * <p>{@link #next} and {@link #end} are facilitator actions (owner-or-{@code ROLE_ADMIN}) — they
 * drive the server-authoritative question clock. {@link #answer}, {@link #state} and
 * {@link #results} identify the acting participant from a bearer token or {@code X-Guest-Token} via
 * {@link SessionCallerResolver}, the same dual-credential shape as the other activities.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/sessions/{id}/quiz/...}.
 *
 * <p>Explicit bean name {@code sessionQuizController} to avoid a bean-name collision with the
 * whiteboard module's own {@code fr.pivot.collaboratif.whiteboard.quiz.QuizController} (both
 * decapitalize to the default {@code quizController}).
 */
@RestController("sessionQuizController")
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions/{id}/quiz")
public class QuizController {

    private final QuizActivityService quizActivityService;
    private final SessionAccessService accessService;
    private final SessionCallerResolver callerResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param quizActivityService the QUIZ business logic service
     * @param accessService       resolves the session with owner-or-admin enforcement
     * @param callerResolver      resolves the acting participant for participant endpoints
     */
    public QuizController(
            final QuizActivityService quizActivityService,
            final SessionAccessService accessService,
            final SessionCallerResolver callerResolver) {
        this.quizActivityService = quizActivityService;
        this.accessService = accessService;
        this.callerResolver = callerResolver;
    }

    /**
     * Opens the next question (US19.3.1) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/next")
    public void next(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        Session session = accessService.resolveSessionForOwnerOrAdmin(id, principal);
        quizActivityService.next(session);
    }

    /**
     * Ends the current question and reveals the answer + leaderboard (US19.3.1) — owner or
     * {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param principal the resolved caller identity
     */
    @PostMapping("/end")
    public void end(@PathVariable final UUID id, final CollaboratifRequestPrincipal principal) {
        Session session = accessService.resolveSessionForOwnerOrAdmin(id, principal);
        quizActivityService.endCurrent(session);
    }

    /**
     * Submits the caller's answer to the live question (US19.3.1) — one per question, graded with a
     * speed bonus; a late or duplicate answer is rejected.
     *
     * @param id          the session's UUID
     * @param request     the answer (question index + selected option indices)
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @PostMapping("/answer")
    public void answer(
            @PathVariable final UUID id,
            @Valid @RequestBody final SubmitAnswerRequest request,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        quizActivityService.answer(session, participantId, request);
    }

    /**
     * Returns the caller's reconnect snapshot (US19.3.1) — current question, own score, and whether
     * they already answered; correct answer/leaderboard only once the question has ended.
     *
     * @param id          the session's UUID
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     * @return the reconnect snapshot
     */
    @GetMapping("/state")
    public QuizStateDto state(@PathVariable final UUID id, final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        return quizActivityService.getState(session, participantId);
    }

    /**
     * Returns the final results — full ranking + per-question correct-rate (US19.3.1),
     * participant-accessible.
     *
     * @param id          the session's UUID
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     * @return the final results
     */
    @GetMapping("/results")
    public QuizResultsDto results(@PathVariable final UUID id, final HttpServletRequest httpRequest) {
        callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        return quizActivityService.getResults(session);
    }
}
