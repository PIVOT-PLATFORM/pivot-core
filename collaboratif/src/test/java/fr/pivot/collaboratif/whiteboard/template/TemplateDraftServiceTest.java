package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.exception.BoardAccessDeniedException;
import fr.pivot.collaboratif.exception.TemplateNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.template.dto.CreateTemplateRequest;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateDraftResponse;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import fr.pivot.collaboratif.whiteboard.template.dto.UpdateTemplateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TemplateDraftService} covering the personal-template CRUD and the
 * draft cycle that edits a template's content (US08.13.2): gallery ordering, ownership-only
 * writes with anti-enumeration 404s, capture-on-create authorization, draft
 * creation/reuse/save/discard, and the delete cascade onto an open draft.
 */
@ExtendWith(MockitoExtension.class)
class TemplateDraftServiceTest {

    @Mock
    private WhiteboardTemplateRepository templateRepository;

    @Mock
    private WhiteboardTemplateService templateService;

    @Mock
    private BoardRepository boardRepository;

    private TemplateDraftService draftService;

    private static final Long TENANT_A = 100L;
    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;

    /** Initialises the service under test with mocked dependencies. */
    @BeforeEach
    void setUp() {
        draftService = new TemplateDraftService(templateRepository, templateService, boardRepository);
    }

    // -------------------------------------------------------------------------
    // listAvailable()
    // -------------------------------------------------------------------------

    /**
     * Given one global template and one personal template owned by the caller, when
     * listAvailable() is called, then the global template comes first with {@code personal=false}
     * and the personal one follows with {@code personal=true}.
     */
    @Test
    void listAvailable_returnsGlobalTemplatesFirstThenPersonalOnes() {
        TemplateResponse global = new TemplateResponse(
                UUID.randomUUID(), "BRAINSTORM", "Brainstorm", null, null, false);
        when(templateService.listGlobalTemplates(TENANT_A)).thenReturn(List.of(global));

        WhiteboardTemplate personal = newTemplate(UUID.randomUUID(), USER_A, "My template", null);
        when(templateRepository.findAllByOwnerIdOrderByUpdatedAtDesc(USER_A))
                .thenReturn(List.of(personal));

        List<TemplateResponse> gallery = draftService.listAvailable(TENANT_A, USER_A);

        assertThat(gallery).hasSize(2);
        assertThat(gallery.get(0).personal()).isFalse();
        assertThat(gallery.get(0).code()).isEqualTo("BRAINSTORM");
        assertThat(gallery.get(1).personal()).isTrue();
        assertThat(gallery.get(1).name()).isEqualTo("My template");
    }

    // -------------------------------------------------------------------------
    // create()
    // -------------------------------------------------------------------------

    /**
     * Given a creation request with no {@code fromBoardId}, when create() is called, then an
     * empty personal template is saved and no board is ever looked up or captured.
     */
    @Test
    void create_withoutFromBoardId_createsEmptyTemplateWithoutCapture() {
        when(templateRepository.save(any(WhiteboardTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        TemplateResponse response = draftService.create(
                new CreateTemplateRequest("My Template", "desc", null), TENANT_A, USER_A);

        assertThat(response.name()).isEqualTo("My Template");
        assertThat(response.personal()).isTrue();
        verify(templateService, never()).captureBoardInto(any(), any(), any());
        verify(boardRepository, never()).findById(any());
    }

    /**
     * Given a {@code fromBoardId} naming a board the caller owns, when create() is called, then
     * the new template's content is captured from that board.
     */
    @Test
    void create_withOwnedFromBoardId_capturesBoardContent() {
        when(templateRepository.save(any(WhiteboardTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UUID boardId = UUID.randomUUID();
        Board source = boardWithOwner(boardId, "Source board", USER_A, TENANT_A);
        when(boardRepository.findById(boardId)).thenReturn(Optional.of(source));

        draftService.create(new CreateTemplateRequest("My Template", null, boardId), TENANT_A, USER_A);

        ArgumentCaptor<WhiteboardTemplate> templateCaptor = ArgumentCaptor.forClass(WhiteboardTemplate.class);
        verify(templateRepository).save(templateCaptor.capture());
        verify(templateService).captureBoardInto(templateCaptor.getValue().getId(), boardId, TENANT_A);
    }

    /**
     * Given a {@code fromBoardId} naming a board owned by someone else, when create() is called,
     * then it throws {@link BoardAccessDeniedException} and never captures anything — being a
     * shared co-editor of the source board is not enough, only ownership is.
     */
    @Test
    void create_withFromBoardIdOwnedBySomeoneElse_throwsBoardAccessDeniedException() {
        when(templateRepository.save(any(WhiteboardTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UUID boardId = UUID.randomUUID();
        Board source = boardWithOwner(boardId, "Someone else's board", USER_B, TENANT_A);
        when(boardRepository.findById(boardId)).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> draftService.create(
                new CreateTemplateRequest("My Template", null, boardId), TENANT_A, USER_A))
                .isInstanceOf(BoardAccessDeniedException.class);
        verify(templateService, never()).captureBoardInto(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // update()
    // -------------------------------------------------------------------------

    /**
     * Given a template the caller owns, when update() is called, then its name and description
     * are replaced and the saved response reflects both.
     */
    @Test
    void update_ownedTemplate_renamesAndRedescribes() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = newTemplate(templateId, USER_A, "Old name", "Old desc");
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.of(template));
        when(templateRepository.save(any(WhiteboardTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        TemplateResponse response = draftService.update(
                templateId, new UpdateTemplateRequest("New name", "New desc"), USER_A);

        assertThat(response.name()).isEqualTo("New name");
        assertThat(response.description()).isEqualTo("New desc");
        assertThat(response.personal()).isTrue();
    }

    /**
     * Given a template owned by someone else, when update() is called, then it throws
     * {@link TemplateNotFoundException} — never an access-denied error, so a caller probing
     * random ids cannot distinguish "does not exist" from "not mine" (anti-enumeration).
     */
    @Test
    void update_templateOwnedBySomeoneElse_throwsTemplateNotFoundException() {
        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> draftService.update(
                templateId, new UpdateTemplateRequest("New name", null), USER_A))
                .isInstanceOf(TemplateNotFoundException.class);
        verify(templateRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // delete()
    // -------------------------------------------------------------------------

    /**
     * Given a template the caller owns, when delete() is called, then any open draft of it is
     * deleted first, then the template itself — ordered so a failure never leaves a draft
     * pointing at a template that no longer exists.
     */
    @Test
    void delete_ownedTemplate_deletesDraftBeforeTemplate() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = newTemplate(templateId, USER_A, "Tmpl", null);
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.of(template));

        draftService.delete(templateId, USER_A);

        InOrder order = inOrder(boardRepository, templateRepository);
        order.verify(boardRepository).deleteByOwnerIdAndTemplateDraftOf(USER_A, templateId);
        order.verify(templateRepository).delete(template);
    }

    /**
     * Given a template owned by someone else, when delete() is called, then it throws
     * {@link TemplateNotFoundException} and neither the draft nor the template is touched.
     */
    @Test
    void delete_templateOwnedBySomeoneElse_throwsTemplateNotFoundException() {
        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> draftService.delete(templateId, USER_A))
                .isInstanceOf(TemplateNotFoundException.class);
        verify(boardRepository, never()).deleteByOwnerIdAndTemplateDraftOf(any(), any());
        verify(templateRepository, never()).delete(any());
    }

    // -------------------------------------------------------------------------
    // editContent() — draft creation and reuse (US08.13.2 core behaviour)
    // -------------------------------------------------------------------------

    /**
     * Given a template with no open draft, when editContent() is called a first time, then it
     * creates a new draft board and initializes it from the template ({@code created=true}); given
     * that same draft is still open, when editContent() is called a second time, then it hands
     * back the very same draft ({@code created=false}) and — the most important behaviour of the
     * whole draft cycle — never calls {@code initializeBoard} again, so the user's in-progress
     * edits are never silently overwritten.
     */
    @Test
    void editContent_secondCall_reusesExistingDraftAndNeverReinitializes() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = newTemplate(templateId, USER_A, "Tmpl", null);
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.of(template));

        UUID draftId = UUID.randomUUID();
        when(boardRepository.findByOwnerIdAndTemplateDraftOf(USER_A, templateId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(boardWithOwner(draftId, "[Template] Tmpl", USER_A, TENANT_A)));
        when(boardRepository.save(any(Board.class))).thenAnswer(inv -> {
            Board board = inv.getArgument(0);
            setBoardId(board, draftId);
            return board;
        });

        TemplateDraftResponse first = draftService.editContent(templateId, TENANT_A, USER_A);
        TemplateDraftResponse second = draftService.editContent(templateId, TENANT_A, USER_A);

        assertThat(first.created()).isTrue();
        assertThat(first.boardId()).isEqualTo(draftId);
        assertThat(second.created()).isFalse();
        assertThat(second.boardId()).isEqualTo(draftId);

        verify(templateService, times(1))
                .initializeBoard(eq(template), eq(draftId), eq(TENANT_A), eq(USER_A));
        verify(boardRepository, times(1)).save(any(Board.class));
    }

    /**
     * Given a template with no open draft, when editContent() is called, then the created draft
     * board's title carries the {@code "[Template] "} prefix and points back at the template via
     * {@code templateDraftOf}.
     */
    @Test
    void editContent_newDraft_titlePrefixedAndMarkedAsDraftOfTemplate() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = newTemplate(templateId, USER_A, "Tmpl", null);
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.of(template));
        when(boardRepository.findByOwnerIdAndTemplateDraftOf(USER_A, templateId)).thenReturn(Optional.empty());
        when(boardRepository.save(any(Board.class))).thenAnswer(inv -> inv.getArgument(0));

        draftService.editContent(templateId, TENANT_A, USER_A);

        ArgumentCaptor<Board> boardCaptor = ArgumentCaptor.forClass(Board.class);
        verify(boardRepository).save(boardCaptor.capture());
        Board draft = boardCaptor.getValue();
        assertThat(draft.getTitle()).isEqualTo("[Template] Tmpl");
        assertThat(draft.getTemplateDraftOf()).isEqualTo(templateId);
    }

    /**
     * Given a template owned by someone else, when editContent() is called, then it throws
     * {@link TemplateNotFoundException} without ever consulting the board repository.
     */
    @Test
    void editContent_templateOwnedBySomeoneElse_throwsTemplateNotFoundException() {
        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> draftService.editContent(templateId, TENANT_A, USER_A))
                .isInstanceOf(TemplateNotFoundException.class);
        verifyNoInteractions(boardRepository);
    }

    // -------------------------------------------------------------------------
    // saveFromDraft()
    // -------------------------------------------------------------------------

    /**
     * Given a template with an open draft, when saveFromDraft() is called, then the draft's live
     * content is captured into the template before the draft is deleted, and the template's name
     * is replaced by the draft's title with the {@code "[Template] "} prefix stripped.
     */
    @Test
    void saveFromDraft_capturesRenamesAndDeletesDraft() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = newTemplate(templateId, USER_A, "Old name", null);
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.of(template));

        UUID draftId = UUID.randomUUID();
        Board draft = boardWithOwner(draftId, "[Template] New Title", USER_A, TENANT_A);
        draft.setTemplateDraftOf(templateId);
        when(boardRepository.findByOwnerIdAndTemplateDraftOf(USER_A, templateId))
                .thenReturn(Optional.of(draft));
        when(templateRepository.save(any(WhiteboardTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        TemplateResponse response = draftService.saveFromDraft(templateId, TENANT_A, USER_A);

        assertThat(response.name()).isEqualTo("New Title");
        InOrder order = inOrder(templateService, boardRepository);
        order.verify(templateService).captureBoardInto(templateId, draftId, TENANT_A);
        order.verify(boardRepository).delete(draft);
    }

    /**
     * Given a template the caller owns but with no open draft, when saveFromDraft() is called,
     * then it throws {@link TemplateNotFoundException} (HTTP 404) and captures nothing.
     */
    @Test
    void saveFromDraft_noOpenDraft_throwsTemplateNotFoundException() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = newTemplate(templateId, USER_A, "Tmpl", null);
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.of(template));
        when(boardRepository.findByOwnerIdAndTemplateDraftOf(USER_A, templateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> draftService.saveFromDraft(templateId, TENANT_A, USER_A))
                .isInstanceOf(TemplateNotFoundException.class);
        verify(templateService, never()).captureBoardInto(any(), any(), any());
        verify(boardRepository, never()).delete(any());
    }

    /**
     * Given a template owned by someone else, when saveFromDraft() is called, then it throws
     * {@link TemplateNotFoundException} without ever consulting the board repository.
     */
    @Test
    void saveFromDraft_templateOwnedBySomeoneElse_throwsTemplateNotFoundException() {
        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> draftService.saveFromDraft(templateId, TENANT_A, USER_A))
                .isInstanceOf(TemplateNotFoundException.class);
        verifyNoInteractions(boardRepository);
    }

    // -------------------------------------------------------------------------
    // discardDraft()
    // -------------------------------------------------------------------------

    /**
     * Given a template the caller owns, when discardDraft() is called, then the open draft is
     * deleted and the template itself is never saved or deleted.
     */
    @Test
    void discardDraft_ownedTemplate_deletesDraftWithoutTouchingTemplate() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = newTemplate(templateId, USER_A, "Tmpl", null);
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.of(template));

        draftService.discardDraft(templateId, USER_A);

        verify(boardRepository).deleteByOwnerIdAndTemplateDraftOf(USER_A, templateId);
        verify(templateRepository, never()).save(any());
        verify(templateRepository, never()).delete(any());
    }

    /**
     * Given a template the caller owns with no draft currently open, when discardDraft() is
     * called, then it completes without error — discarding nothing is a satisfied intent, not a
     * failure.
     */
    @Test
    void discardDraft_noOpenDraft_isIdempotentNoOp() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = newTemplate(templateId, USER_A, "Tmpl", null);
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.of(template));
        when(boardRepository.deleteByOwnerIdAndTemplateDraftOf(USER_A, templateId)).thenReturn(0L);

        assertThatCode(() -> draftService.discardDraft(templateId, USER_A)).doesNotThrowAnyException();
    }

    /**
     * Given a template owned by someone else, when discardDraft() is called, then it throws
     * {@link TemplateNotFoundException} without ever consulting the board repository.
     */
    @Test
    void discardDraft_templateOwnedBySomeoneElse_throwsTemplateNotFoundException() {
        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndOwnerId(templateId, USER_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> draftService.discardDraft(templateId, USER_A))
                .isInstanceOf(TemplateNotFoundException.class);
        verifyNoInteractions(boardRepository);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WhiteboardTemplate newTemplate(
            final UUID id, final Long ownerId, final String name, final String description) {
        return new WhiteboardTemplate(id, TENANT_A, ownerId, name, description, null);
    }

    /** Creates a real {@link Board}, simulating a JPA-persisted entity with a server-assigned id. */
    private Board boardWithOwner(
            final UUID id, final String title, final Long ownerId, final Long tenantId) {
        Board board = new Board(title, tenantId, ownerId, Instant.now());
        setBoardId(board, id);
        return board;
    }

    /** Reflectively sets a {@link Board}'s server-generated id, unreachable via any public setter. */
    private void setBoardId(final Board board, final UUID id) {
        try {
            Field field = Board.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(board, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set board id in test", e);
        }
    }
}
