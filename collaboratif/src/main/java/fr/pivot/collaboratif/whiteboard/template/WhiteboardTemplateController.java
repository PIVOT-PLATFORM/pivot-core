package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.web.CollaboratifApiPaths;
import fr.pivot.collaboratif.whiteboard.template.dto.CreateTemplateRequest;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateDraftResponse;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import fr.pivot.collaboratif.whiteboard.template.dto.UpdateTemplateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for the whiteboard template gallery (US08.4.1) and the personal-template
 * lifecycle (US08.13.2), under {@code /collaboratif/whiteboard/templates}.
 *
 * <p>Board initialization from a template is exposed on
 * {@code POST /whiteboard/boards?templateId=...} — see {@code BoardController}, which
 * reuses the existing US08.1.1 board-creation flow rather than duplicating it.
 *
 * <p>The full path (including the application context) is
 * {@code /api/collaboratif/whiteboard/templates}.
 *
 * <p><strong>Identity never comes from the request.</strong> Every method takes the caller's
 * user and tenant from the resolved {@link CollaboratifRequestPrincipal}; no path variable, body
 * field or header is ever consulted for it.
 */
@RestController
@RequestMapping(CollaboratifApiPaths.BASE + "/whiteboard/templates")
public class WhiteboardTemplateController {

    private final TemplateDraftService draftService;

    /**
     * Creates the controller with its required service dependency.
     *
     * @param draftService personal-template CRUD and draft lifecycle
     */
    public WhiteboardTemplateController(final TemplateDraftService draftService) {
        this.draftService = draftService;
    }

    /**
     * Lists the templates the caller may use in the "New board" gallery — global ones, then their
     * own personal templates.
     *
     * @param principal the resolved caller identity (user + tenant)
     * @return the ordered gallery
     */
    @GetMapping
    public List<TemplateResponse> list(final CollaboratifRequestPrincipal principal) {
        return draftService.listAvailable(principal.tenantId(), principal.userId());
    }

    /**
     * Creates a personal template, optionally capturing an existing board's content.
     *
     * @param request   the creation payload
     * @param principal the resolved caller identity
     * @return the created template
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(
            @Valid @RequestBody final CreateTemplateRequest request,
            final CollaboratifRequestPrincipal principal) {
        return draftService.create(request, principal.tenantId(), principal.userId());
    }

    /**
     * Renames or re-describes a template the caller owns.
     *
     * @param templateId the template
     * @param request    the new metadata
     * @param principal  the resolved caller identity
     * @return the updated template
     */
    @PatchMapping("/{templateId}")
    public TemplateResponse update(
            @PathVariable final UUID templateId,
            @Valid @RequestBody final UpdateTemplateRequest request,
            final CollaboratifRequestPrincipal principal) {
        return draftService.update(templateId, request, principal.userId());
    }

    /**
     * Deletes a template the caller owns. Boards already created from it are unaffected.
     *
     * @param templateId the template
     * @param principal  the resolved caller identity
     */
    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable final UUID templateId,
            final CollaboratifRequestPrincipal principal) {
        draftService.delete(templateId, principal.userId());
    }

    /**
     * Opens the draft board used to edit a template's content, reusing one if already open.
     *
     * <p>Answers <strong>200</strong> in both cases, never 201: from the caller's point of view the
     * outcome is identical — a board to open — and a status that varied would push clients to
     * branch on something that carries no meaning for them. The {@code created} flag in the body
     * conveys the distinction for the one client that does care.
     *
     * @param templateId the template to edit
     * @param principal  the resolved caller identity
     * @return the draft board id and whether this call created it
     */
    @PostMapping("/{templateId}/edit-content")
    public TemplateDraftResponse editContent(
            @PathVariable final UUID templateId,
            final CollaboratifRequestPrincipal principal) {
        return draftService.editContent(templateId, principal.tenantId(), principal.userId());
    }

    /**
     * Saves the draft's content back into the template and deletes the draft.
     *
     * @param templateId the template being edited
     * @param principal  the resolved caller identity
     * @return the saved template
     */
    @PostMapping("/{templateId}/save-from-draft")
    public TemplateResponse saveFromDraft(
            @PathVariable final UUID templateId,
            final CollaboratifRequestPrincipal principal) {
        return draftService.saveFromDraft(templateId, principal.tenantId(), principal.userId());
    }

    /**
     * Throws away the caller's draft for a template, leaving the template untouched.
     *
     * <p>Idempotent: 204 whether or not a draft was open.
     *
     * @param templateId the template
     * @param principal  the resolved caller identity
     */
    @PostMapping("/{templateId}/discard-draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discardDraft(
            @PathVariable final UUID templateId,
            final CollaboratifRequestPrincipal principal) {
        draftService.discardDraft(templateId, principal.userId());
    }
}
