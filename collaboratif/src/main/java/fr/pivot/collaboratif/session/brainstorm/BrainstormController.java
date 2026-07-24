package fr.pivot.collaboratif.session.brainstorm;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionAccessService;
import fr.pivot.collaboratif.session.SessionCallerResolver;
import fr.pivot.collaboratif.session.brainstorm.dto.AddCardRequest;
import fr.pivot.collaboratif.session.brainstorm.dto.BrainstormCardDto;
import fr.pivot.collaboratif.session.brainstorm.dto.CategorizeCardRequest;
import fr.pivot.collaboratif.session.brainstorm.dto.UpdateCardRequest;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the BRAINSTORM activity (US19.3.4).
 *
 * <p>Card CRUD ({@link #add}/{@link #update}/{@link #delete}) and the {@link #list} hydration read
 * identify the acting participant from either a bearer token or an {@code X-Guest-Token} header via
 * {@link SessionCallerResolver} — the same dual-credential shape as POLL/WORDCLOUD/Q&A. Ownership
 * of edit/delete is enforced in the service, not here. {@link #categorize} is a facilitator action
 * and uses the standard owner-or-{@code ROLE_ADMIN} resolution.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/sessions/{id}/brainstorm/...}.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/sessions/{id}/brainstorm")
public class BrainstormController {

    private final BrainstormActivityService brainstormActivityService;
    private final SessionAccessService accessService;
    private final SessionCallerResolver callerResolver;

    /**
     * Creates the controller with its required dependencies.
     *
     * @param brainstormActivityService the BRAINSTORM business logic service
     * @param accessService             resolves the session with owner-or-admin enforcement
     * @param callerResolver            resolves the acting participant for participant endpoints
     */
    public BrainstormController(
            final BrainstormActivityService brainstormActivityService,
            final SessionAccessService accessService,
            final SessionCallerResolver callerResolver) {
        this.brainstormActivityService = brainstormActivityService;
        this.accessService = accessService;
        this.callerResolver = callerResolver;
    }

    /**
     * Adds a card (US19.3.4).
     *
     * @param id          the session's UUID
     * @param request     the card creation request
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @PostMapping("/cards")
    public void add(
            @PathVariable final UUID id,
            @Valid @RequestBody final AddCardRequest request,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        brainstormActivityService.addCard(session, participantId, request.text(), request.color());
    }

    /**
     * Edits a card the caller authored (US19.3.4) — a non-author caller gets 403.
     *
     * @param id          the session's UUID
     * @param cardId      the card to edit
     * @param request     the edit request
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @PatchMapping("/cards/{cardId}")
    public void update(
            @PathVariable final UUID id,
            @PathVariable final UUID cardId,
            @Valid @RequestBody final UpdateCardRequest request,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        brainstormActivityService.editCard(session, participantId, cardId, request.text(), request.color());
    }

    /**
     * Deletes a card the caller authored (US19.3.4) — a non-author caller gets 403.
     *
     * @param id          the session's UUID
     * @param cardId      the card to delete
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     */
    @DeleteMapping("/cards/{cardId}")
    public void delete(
            @PathVariable final UUID id,
            @PathVariable final UUID cardId,
            final HttpServletRequest httpRequest) {
        UUID participantId = callerResolver.resolveParticipantId(httpRequest, id);
        Session session = accessService.loadById(id);
        brainstormActivityService.deleteCard(session, participantId, cardId);
    }

    /**
     * Groups a card under a category (US19.3.4, facilitator) — owner or {@code ROLE_ADMIN} only.
     *
     * @param id        the session's UUID
     * @param cardId    the card to categorize
     * @param request   the category request (a blank/null category clears the grouping)
     * @param principal the resolved caller identity
     */
    @PostMapping("/cards/{cardId}/category")
    public void categorize(
            @PathVariable final UUID id,
            @PathVariable final UUID cardId,
            @Valid @RequestBody final CategorizeCardRequest request,
            final CollaboratifRequestPrincipal principal) {
        accessService.resolveSessionForOwnerOrAdmin(id, principal);
        brainstormActivityService.categorizeCard(id, cardId, request.category());
    }

    /**
     * Lists the session's cards (US19.3.4) — participant-accessible, used to hydrate the view on
     * join and reconnect.
     *
     * @param id          the session's UUID
     * @param httpRequest the raw HTTP request, used to resolve the acting participant
     * @return the cards, oldest first
     */
    @GetMapping("/cards")
    public List<BrainstormCardDto> list(@PathVariable final UUID id, final HttpServletRequest httpRequest) {
        callerResolver.resolveParticipantId(httpRequest, id);
        return brainstormActivityService.getCards(id);
    }
}
