package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.TemplateNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.template.dto.CreateTemplateRequest;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateDraftResponse;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import fr.pivot.collaboratif.whiteboard.template.dto.UpdateTemplateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Personal-template CRUD and the draft cycle that edits a template's content (US08.13.2).
 *
 * <p>A template's content cannot be edited in place: it is a set of denormalized element payloads,
 * not a live canvas. Editing therefore goes through a throwaway <em>draft board</em> — a real
 * {@link Board} carrying {@code templateDraftOf}, opened in the ordinary canvas, then either
 * captured back into the template or discarded. That draft is invisible in every board listing, so
 * it never pollutes the user's own list of boards.
 *
 * <p>Separate from {@link WhiteboardTemplateService}, which owns the gallery and the
 * snapshot↔board materialization, so that neither class carries the other's concerns.
 *
 * <p><strong>Ownership is the only authorization here.</strong> Every write requires
 * {@code template.ownerId == caller}. Read access beyond ownership — sharing a template with the
 * tenant or with named people — is US08.13.5's concern and deliberately absent.
 */
@Service
public class TemplateDraftService {

    /** Prefix marking a board as a template draft in the UI; stripped again on save. */
    private static final String DRAFT_TITLE_PREFIX = "[Template] ";

    private final WhiteboardTemplateRepository templateRepository;
    private final WhiteboardTemplateService templateService;
    private final BoardRepository boardRepository;

    /**
     * Creates the service.
     *
     * @param templateRepository template persistence
     * @param templateService    gallery + snapshot materialization
     * @param boardRepository    board persistence, for the draft boards
     */
    public TemplateDraftService(
            final WhiteboardTemplateRepository templateRepository,
            final WhiteboardTemplateService templateService,
            final BoardRepository boardRepository) {
        this.templateRepository = templateRepository;
        this.templateService = templateService;
        this.boardRepository = boardRepository;
    }

    /**
     * Lists the templates the caller may use: the global ones, then their own.
     *
     * <p>Global first because they are the stable, always-present starting points; personal ones
     * follow, most recently edited first.
     *
     * @param tenantId the caller's tenant
     * @param userId   the caller's {@code public.users.id}
     * @return the gallery, global templates first
     */
    @Transactional(readOnly = true)
    public List<TemplateResponse> listAvailable(final Long tenantId, final Long userId) {
        List<TemplateResponse> gallery =
                new ArrayList<>(templateService.listGlobalTemplates(tenantId));
        templateRepository.findAllByOwnerIdOrderByUpdatedAtDesc(userId).stream()
                .map(t -> TemplateResponse.from(t, true))
                .forEach(gallery::add);
        return gallery;
    }

    /**
     * Creates a personal template, optionally capturing an existing board's content.
     *
     * @param request  the creation payload
     * @param tenantId the caller's tenant
     * @param userId   the caller's {@code public.users.id} — becomes the template's owner
     * @return the created template
     * @throws BoardAccessDeniedException if {@code fromBoardId} names a board the caller does not
     *                                    own
     */
    @Transactional
    public TemplateResponse create(
            final CreateTemplateRequest request, final Long tenantId, final Long userId) {
        WhiteboardTemplate template = templateRepository.save(new WhiteboardTemplate(
                UUID.randomUUID(), tenantId, userId, request.name(), request.description(), null));

        if (request.fromBoardId() != null) {
            // Ownership, not mere access: capturing a board copies its entire content into an
            // object the copier alone then controls, so being a shared co-editor is not enough.
            Board source = boardRepository.findById(request.fromBoardId())
                    .filter(b -> tenantId.equals(b.getTenantId()) && b.getDeletedAt() == null)
                    .orElseThrow(() -> new TemplateNotFoundException(request.fromBoardId()));
            if (!userId.equals(source.getOwnerId())) {
                throw new BoardAccessDeniedException(request.fromBoardId());
            }
            templateService.captureBoardInto(template.getId(), source.getId(), tenantId);
        }
        return TemplateResponse.from(template, true);
    }

    /**
     * Renames or re-describes a template the caller owns.
     *
     * @param templateId the template
     * @param request    the new metadata
     * @param userId     the caller's {@code public.users.id}
     * @return the updated template
     * @throws TemplateNotFoundException if absent or owned by someone else
     */
    @Transactional
    public TemplateResponse update(
            final UUID templateId, final UpdateTemplateRequest request, final Long userId) {
        WhiteboardTemplate template = requireOwned(templateId, userId);
        template.rename(request.name());
        template.setDescription(request.description());
        return TemplateResponse.from(templateRepository.save(template), true);
    }

    /**
     * Deletes a template the caller owns, along with any open draft of it.
     *
     * <p>Boards already created from the template are untouched: the materialization copies
     * content rather than referencing it, so there is nothing to break.
     *
     * @param templateId the template
     * @param userId     the caller's {@code public.users.id}
     * @throws TemplateNotFoundException if absent or owned by someone else
     */
    @Transactional
    public void delete(final UUID templateId, final Long userId) {
        WhiteboardTemplate template = requireOwned(templateId, userId);
        // Ordered before the template delete so a failure leaves no draft pointing at a template
        // that no longer exists — the pointer carries no foreign key to protect it.
        boardRepository.deleteByOwnerIdAndTemplateDraftOf(userId, templateId);
        templateRepository.delete(template);
    }

    /**
     * Opens the draft board used to edit a template's content, reusing one if already open.
     *
     * <p>An existing draft is handed back <strong>as is</strong>, with no resynchronization from
     * the template — even if the template changed in between. Reconciling the two would mean
     * choosing which side wins and silently discarding the other; handing back the user's own
     * unsaved work never destroys anything.
     *
     * @param templateId the template to edit
     * @param tenantId   the caller's tenant
     * @param userId     the caller's {@code public.users.id}
     * @return the draft board and whether this call created it
     * @throws TemplateNotFoundException if absent or owned by someone else
     */
    @Transactional
    public TemplateDraftResponse editContent(
            final UUID templateId, final Long tenantId, final Long userId) {
        WhiteboardTemplate template = requireOwned(templateId, userId);

        return boardRepository.findByOwnerIdAndTemplateDraftOf(userId, templateId)
                .map(existing -> new TemplateDraftResponse(existing.getId(), false))
                .orElseGet(() -> {
                    Board draft = new Board(
                            DRAFT_TITLE_PREFIX + template.getName(), tenantId, userId, Instant.now());
                    draft.setTemplateDraftOf(templateId);
                    Board saved = boardRepository.save(draft);
                    templateService.initializeBoard(template, saved.getId(), tenantId, userId);
                    return new TemplateDraftResponse(saved.getId(), true);
                });
    }

    /**
     * Captures the draft's live content back into the template, then deletes the draft.
     *
     * @param templateId the template being edited
     * @param tenantId   the caller's tenant
     * @param userId     the caller's {@code public.users.id}
     * @return the saved template
     * @throws TemplateNotFoundException if the template is absent, owned by someone else, or has
     *                                   no open draft
     */
    @Transactional
    public TemplateResponse saveFromDraft(
            final UUID templateId, final Long tenantId, final Long userId) {
        WhiteboardTemplate template = requireOwned(templateId, userId);
        Board draft = boardRepository.findByOwnerIdAndTemplateDraftOf(userId, templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));

        templateService.captureBoardInto(templateId, draft.getId(), tenantId);
        // The draft's title carries the prefix only so the canvas reads as a template edit; the
        // template itself must never inherit it.
        template.rename(stripDraftPrefix(draft.getTitle()));
        template.touch();
        WhiteboardTemplate saved = templateRepository.save(template);

        boardRepository.delete(draft);
        return TemplateResponse.from(saved, true);
    }

    /**
     * Throws away the caller's draft for a template, leaving the template untouched.
     *
     * <p>Idempotent: discarding when nothing is open is a no-op, not an error — the caller's intent
     * ("I don't want this draft") is already satisfied.
     *
     * @param templateId the template
     * @param userId     the caller's {@code public.users.id}
     * @throws TemplateNotFoundException if the template is absent or owned by someone else
     */
    @Transactional
    public void discardDraft(final UUID templateId, final Long userId) {
        requireOwned(templateId, userId);
        boardRepository.deleteByOwnerIdAndTemplateDraftOf(userId, templateId);
    }

    /**
     * Loads a template the caller owns, or fails as if it did not exist.
     *
     * <p>404 rather than 403 on someone else's template: answering "forbidden" would confirm that
     * a template with this id exists, which is exactly what an enumeration attempt is looking for.
     *
     * @param templateId the template
     * @param userId     the caller's {@code public.users.id}
     * @return the owned template
     * @throws TemplateNotFoundException if absent or owned by someone else
     */
    private WhiteboardTemplate requireOwned(final UUID templateId, final Long userId) {
        return templateRepository.findByIdAndOwnerId(templateId, userId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    /**
     * Removes the draft marker from a title, if present.
     *
     * @param title the draft board's title
     * @return the title without the draft prefix
     */
    private static String stripDraftPrefix(final String title) {
        return title.startsWith(DRAFT_TITLE_PREFIX) ? title.substring(DRAFT_TITLE_PREFIX.length()) : title;
    }
}
