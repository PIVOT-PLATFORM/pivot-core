package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuizSessionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing read-only access to a board's quiz sessions (Quiz feature). Complements
 * the STOMP mutation channel ({@code quiz:*}, see {@code QuizActionService}) so a client that
 * connects mid-quiz — or reopens the board — can rehydrate the live quiz and the last results
 * without waiting for the next broadcast (frontend {@code board.store.ts#loadQuiz}). Calques
 * {@code VoteController} (Vote feature, the model this quiz module mirrors).
 *
 * <p>All endpoints require a valid {@code Authorization: Bearer <token>} header, resolved into a
 * {@link CollaboratifRequestPrincipal} by {@code CollaboratifRequestPrincipalResolver} (EN08.3).
 * Tenant and user identity always come from the resolved principal — never from the request. A
 * board that is unknown, cross-tenant, or that the caller is not a member of resolves to HTTP 404
 * (never 403), so the endpoint never confirms a resource the caller may not see. See
 * {@link QuizQueryService} for the masking rules applied to the response (decision D4).
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/whiteboard/boards/{boardId}/quiz/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/whiteboard/boards/{boardId}/quiz")
public class QuizController {

    private final QuizQueryService quizQueryService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param quizQueryService the quiz read service
     */
    public QuizController(final QuizQueryService quizQueryService) {
        this.quizQueryService = quizQueryService;
    }

    /**
     * Returns the board's currently active quiz session, masked according to the current
     * question's state, or {@code null} (HTTP 200 with an empty body) when no quiz is running.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     * @return the active session, or {@code null} if none
     */
    @GetMapping("/current")
    public QuizSessionResponse current(
            @PathVariable final UUID boardId,
            final CollaboratifRequestPrincipal principal) {
        return quizQueryService.current(boardId, principal.userId(), principal.tenantId());
    }

    /**
     * Returns the board's most recently closed quiz session with its aggregated results, or
     * {@code null} (HTTP 200 with an empty body) when the board has never held one.
     *
     * @param boardId   the board UUID from the path
     * @param principal the resolved caller identity
     * @return the last closed session, or {@code null} if none
     */
    @GetMapping("/last")
    public QuizSessionResponse last(
            @PathVariable final UUID boardId,
            final CollaboratifRequestPrincipal principal) {
        return quizQueryService.last(boardId, principal.userId(), principal.tenantId());
    }
}
