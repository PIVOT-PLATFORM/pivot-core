package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.exception.InvalidCanvasElementException;
import fr.pivot.collaboratif.exception.InvalidTemplateIdException;
import fr.pivot.collaboratif.exception.TemplateNotFoundException;
import fr.pivot.collaboratif.exception.WhiteboardModuleDisabledException;
import fr.pivot.collaboratif.whiteboard.board.WhiteboardModuleCheck;
import fr.pivot.collaboratif.whiteboard.canvas.BoardField;
import fr.pivot.collaboratif.whiteboard.canvas.BoardFieldRepository;
import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CardConnection;
import fr.pivot.collaboratif.whiteboard.canvas.CardConnectionRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardFieldValueRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardType;
import fr.pivot.collaboratif.whiteboard.canvas.FieldType;
import fr.pivot.collaboratif.whiteboard.canvas.Frame;
import fr.pivot.collaboratif.whiteboard.canvas.FrameRepository;
import fr.pivot.collaboratif.whiteboard.canvas.ShapeStyleSanitizer;
import fr.pivot.collaboratif.whiteboard.canvas.TemplateElementType;
import fr.pivot.collaboratif.whiteboard.canvas.TemplateElementValidator;
import fr.pivot.collaboratif.whiteboard.template.dto.TemplateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WhiteboardTemplateService} covering all business branches (US08.4.1,
 * re-platformed EN08.x): gallery listing, module-disabled guard, templateId resolution
 * (malformed UUID, unknown template), board initialization materializing real Frame/Card/
 * CardConnection/BoardField/CardFieldValue rows, and save-as-template capture.
 */
@ExtendWith(MockitoExtension.class)
class WhiteboardTemplateServiceTest {

    @Mock
    private WhiteboardTemplateRepository templateRepository;

    @Mock
    private WhiteboardTemplateElementRepository templateElementRepository;

    @Mock
    private TemplateElementValidator templateElementValidator;

    @Mock
    private ShapeStyleSanitizer shapeStyleSanitizer;

    @Mock
    private FrameRepository frameRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CardConnectionRepository cardConnectionRepository;

    @Mock
    private BoardFieldRepository boardFieldRepository;

    @Mock
    private CardFieldValueRepository cardFieldValueRepository;

    @Mock
    private WhiteboardModuleCheck moduleCheck;

    private WhiteboardTemplateService templateService;

    private static final Long TENANT_A = 100L;
    private static final Long USER_A = 1L;
    private static final UUID BOARD_A = UUID.randomUUID();

    /** Initialises the service under test with mocked dependencies and a real Jackson mapper. */
    @BeforeEach
    void setUp() {
        templateService = new WhiteboardTemplateService(
                templateRepository, templateElementRepository, templateElementValidator,
                shapeStyleSanitizer, frameRepository, cardRepository, cardConnectionRepository,
                boardFieldRepository, cardFieldValueRepository, moduleCheck, new ObjectMapper());
        lenient().when(frameRepository.save(any(Frame.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(boardFieldRepository.save(any(BoardField.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // listGlobalTemplates()
    // -------------------------------------------------------------------------

    /**
     * Given the module is enabled, when listGlobalTemplates() is called,
     * then it returns the ordered global templates as response DTOs.
     */
    @Test
    void listGlobalTemplates_whenModuleEnabled_returnsOrderedTemplates() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(true);
        WhiteboardTemplate template = mockTemplate(UUID.randomUUID(), "BRAINSTORM", "Brainstorm");
        when(templateRepository.findAllByTenantIdIsNullOrderByDisplayOrderAsc())
                .thenReturn(List.of(template));

        List<TemplateResponse> responses = templateService.listGlobalTemplates(TENANT_A);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).code()).isEqualTo("BRAINSTORM");
    }

    /**
     * Given the module is disabled for the tenant, when listGlobalTemplates() is called,
     * then it throws {@link WhiteboardModuleDisabledException} (mapped to HTTP 403).
     */
    @Test
    void listGlobalTemplates_whenModuleDisabled_throwsWhiteboardModuleDisabledException() {
        when(moduleCheck.isEnabled(TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> templateService.listGlobalTemplates(TENANT_A))
                .isInstanceOf(WhiteboardModuleDisabledException.class);
    }

    // -------------------------------------------------------------------------
    // resolveGlobalTemplate()
    // -------------------------------------------------------------------------

    /**
     * Given a malformed UUID string, when resolveGlobalTemplate() is called,
     * then it throws {@link InvalidTemplateIdException} (mapped to HTTP 400
     * INVALID_TEMPLATE_ID) without querying the repository.
     */
    @Test
    void resolveGlobalTemplate_withMalformedUuid_throwsInvalidTemplateIdException() {
        assertThatThrownBy(() -> templateService.resolveGlobalTemplate("not-a-uuid"))
                .isInstanceOf(InvalidTemplateIdException.class);
    }

    /**
     * Given a well-formed UUID that does not match any global template, when
     * resolveGlobalTemplate() is called, then it throws {@link TemplateNotFoundException}
     * (mapped to HTTP 404, no existence leak).
     */
    @Test
    void resolveGlobalTemplate_withUnknownUuid_throwsTemplateNotFoundException() {
        UUID templateId = UUID.randomUUID();
        when(templateRepository.findByIdAndTenantIdIsNull(templateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.resolveGlobalTemplate(templateId.toString()))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    /**
     * Given a well-formed UUID matching an existing global template, when
     * resolveGlobalTemplate() is called, then it returns that template.
     */
    @Test
    void resolveGlobalTemplate_withValidUuid_returnsTemplate() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "RETROSPECTIVE", "Retrospective");
        when(templateRepository.findByIdAndTenantIdIsNull(templateId))
                .thenReturn(Optional.of(template));

        WhiteboardTemplate resolved = templateService.resolveGlobalTemplate(templateId.toString());

        assertThat(resolved.getId()).isEqualTo(templateId);
    }

    // -------------------------------------------------------------------------
    // initializeBoard() — materializes real Frame/Card/CardConnection/BoardField/CardFieldValue
    // -------------------------------------------------------------------------

    /**
     * Given a template with a FRAME and a CARD element, when initializeBoard() is called,
     * then each is validated and persisted as a real {@link Frame}/{@link Card} scoped to the
     * new board and tenant.
     */
    @Test
    void initializeBoard_materializesFrameAndCard() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "BRAINSTORM", "Brainstorm");
        WhiteboardTemplateElement frameElement = mockElement(
                TemplateElementType.FRAME, null,
                "{\"title\":\"Idées\",\"posX\":0,\"posY\":0,\"width\":300,\"height\":200,"
                        + "\"color\":\"#94A3B8\",\"layer\":0}");
        WhiteboardTemplateElement cardElement = mockElement(
                TemplateElementType.CARD, null,
                "{\"type\":\"TEXT\",\"content\":\"Idée 1\",\"posX\":20,\"posY\":20,"
                        + "\"width\":180,\"height\":120,\"color\":\"#FEF08A\",\"layer\":1}");
        when(templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(templateId))
                .thenReturn(List.of(frameElement, cardElement));

        templateService.initializeBoard(template, BOARD_A, TENANT_A, USER_A);

        verify(templateElementValidator).validate(eq(TemplateElementType.FRAME), any());
        verify(templateElementValidator).validate(eq(TemplateElementType.CARD), any());

        ArgumentCaptor<Frame> frameCaptor = ArgumentCaptor.forClass(Frame.class);
        verify(frameRepository).save(frameCaptor.capture());
        assertThat(frameCaptor.getValue().getBoardId()).isEqualTo(BOARD_A);
        assertThat(frameCaptor.getValue().getTenantId()).isEqualTo(TENANT_A);
        assertThat(frameCaptor.getValue().getTitle()).isEqualTo("Idées");

        ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(cardCaptor.capture());
        assertThat(cardCaptor.getValue().getBoardId()).isEqualTo(BOARD_A);
        assertThat(cardCaptor.getValue().getType()).isEqualTo(CardType.TEXT);
        assertThat(cardCaptor.getValue().getContent()).isEqualTo("Idée 1");
    }

    /**
     * Given a SHAPE card element, when initializeBoard() is called, then its content is
     * normalized through {@link ShapeStyleSanitizer} before persistence — the same hardening
     * every user-drawn SHAPE card content goes through.
     */
    @Test
    void initializeBoard_shapeCard_sanitizesContent() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "MINDMAP", "Mindmap");
        WhiteboardTemplateElement shapeElement = mockElement(
                TemplateElementType.CARD, null,
                "{\"type\":\"SHAPE\",\"content\":\"circle|#A5B4FC|#EEF2FF|1|0|tlbr\",\"posX\":0,"
                        + "\"posY\":0,\"width\":100,\"height\":100,\"color\":\"#FFFFFF\",\"layer\":1}");
        when(templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(templateId))
                .thenReturn(List.of(shapeElement));
        when(shapeStyleSanitizer.sanitize("circle|#A5B4FC|#EEF2FF|1|0|tlbr"))
                .thenReturn("circle|#A5B4FC|#EEF2FF|1|0|tlbr");

        templateService.initializeBoard(template, BOARD_A, TENANT_A, USER_A);

        verify(shapeStyleSanitizer).sanitize("circle|#A5B4FC|#EEF2FF|1|0|tlbr");
    }

    /**
     * Given a CARD element referenced by a CONNECTION element via localKey/fromKey-toKey, when
     * initializeBoard() is called, then the connection is materialized after both endpoint
     * cards, regardless of the connection's position in displayOrder, resolving the keys to the
     * cards' generated UUIDs.
     */
    @Test
    void initializeBoard_connectionResolvesCardLocalKeys() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "MINDMAP", "Mindmap");
        WhiteboardTemplateElement cardA = mockElement(
                TemplateElementType.CARD, "cardA",
                "{\"type\":\"LABEL\",\"content\":\"Centre\",\"posX\":0,\"posY\":0,"
                        + "\"width\":100,\"height\":60,\"layer\":1}");
        WhiteboardTemplateElement cardB = mockElement(
                TemplateElementType.CARD, "cardB",
                "{\"type\":\"LABEL\",\"content\":\"Branche\",\"posX\":200,\"posY\":0,"
                        + "\"width\":100,\"height\":60,\"layer\":1}");
        WhiteboardTemplateElement connection = mockElement(
                TemplateElementType.CONNECTION, null,
                "{\"fromKey\":\"cardA\",\"toKey\":\"cardB\",\"shape\":\"curved\","
                        + "\"lineStyle\":\"solid\",\"startCap\":\"none\",\"endCap\":\"arrow\"}");
        when(templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(templateId))
                .thenReturn(List.of(connection, cardA, cardB));

        UUID cardAId = UUID.randomUUID();
        UUID cardBId = UUID.randomUUID();
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
            Card card = inv.getArgument(0);
            if ("Centre".equals(card.getContent())) {
                setId(card, cardAId);
            } else {
                setId(card, cardBId);
            }
            return card;
        });

        templateService.initializeBoard(template, BOARD_A, TENANT_A, USER_A);

        ArgumentCaptor<CardConnection> connectionCaptor = ArgumentCaptor.forClass(CardConnection.class);
        verify(cardConnectionRepository).save(connectionCaptor.capture());
        assertThat(connectionCaptor.getValue().getFromId()).isEqualTo(cardAId);
        assertThat(connectionCaptor.getValue().getToId()).isEqualTo(cardBId);
        assertThat(connectionCaptor.getValue().getEndCap()).isEqualTo("arrow");
    }

    /**
     * Given a CONNECTION element referencing an unknown localKey, when initializeBoard() is
     * called, then it throws {@link InvalidCanvasElementException} — an internal invariant
     * violation on seed data.
     */
    @Test
    void initializeBoard_connectionWithUnknownKey_throwsInvalidCanvasElementException() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "MINDMAP", "Mindmap");
        WhiteboardTemplateElement connection = mockElement(
                TemplateElementType.CONNECTION, null,
                "{\"fromKey\":\"ghost\",\"toKey\":\"ghost2\",\"shape\":\"curved\","
                        + "\"lineStyle\":\"solid\",\"startCap\":\"none\",\"endCap\":\"none\"}");
        when(templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(templateId))
                .thenReturn(List.of(connection));

        assertThatThrownBy(() -> templateService.initializeBoard(template, BOARD_A, TENANT_A, USER_A))
                .isInstanceOf(InvalidCanvasElementException.class);
    }

    /**
     * Given a FIELD element and a CARD element referenced by a FIELD_VALUE element, when
     * initializeBoard() is called, then the value is materialized after both, resolving
     * cardKey/fieldKey to their generated UUIDs.
     */
    @Test
    void initializeBoard_fieldValueResolvesCardAndFieldLocalKeys() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "RISK_ANALYSIS", "Risk analysis");
        WhiteboardTemplateElement fieldElement = mockElement(
                TemplateElementType.FIELD, "probField",
                "{\"name\":\"Probabilité\",\"type\":\"NUMBER\",\"order\":0}");
        WhiteboardTemplateElement cardElement = mockElement(
                TemplateElementType.CARD, "riskCard",
                "{\"type\":\"TEXT\",\"content\":\"Risque 1\",\"posX\":0,\"posY\":0,"
                        + "\"width\":180,\"height\":120,\"layer\":1}");
        WhiteboardTemplateElement fieldValue = mockElement(
                TemplateElementType.FIELD_VALUE, null,
                "{\"cardKey\":\"riskCard\",\"fieldKey\":\"probField\",\"value\":\"3\"}");
        when(templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(templateId))
                .thenReturn(List.of(fieldElement, cardElement, fieldValue));

        UUID fieldId = UUID.randomUUID();
        when(boardFieldRepository.save(any(BoardField.class))).thenAnswer(inv -> {
            BoardField field = inv.getArgument(0);
            setFieldId(field, fieldId);
            return field;
        });
        UUID cardId = UUID.randomUUID();
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> {
            Card card = inv.getArgument(0);
            setId(card, cardId);
            return card;
        });

        templateService.initializeBoard(template, BOARD_A, TENANT_A, USER_A);

        verify(cardFieldValueRepository).save(argThat(value ->
                value.getCardId().equals(cardId) && value.getFieldId().equals(fieldId)
                        && "3".equals(value.getValue())));
    }

    /**
     * Given a template with no elements, when initializeBoard() is called,
     * then no materialization repository is invoked.
     */
    @Test
    void initializeBoard_withNoElements_materializesNothing() {
        UUID templateId = UUID.randomUUID();
        WhiteboardTemplate template = mockTemplate(templateId, "EMPTY", "Empty");
        when(templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(templateId))
                .thenReturn(List.of());

        templateService.initializeBoard(template, BOARD_A, TENANT_A, USER_A);

        verify(templateElementValidator, never()).validate(any(), any());
        verify(frameRepository, never()).save(any());
        verify(cardRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // createFromBoard() — US08.2.4 save as template
    // -------------------------------------------------------------------------

    /**
     * Given a board with a frame and a card, when createFromBoard() is called, then a
     * tenant-owned template header is saved and one FRAME + one CARD element is captured, each
     * keyed by the source entity's own UUID.
     */
    @Test
    void createFromBoard_capturesFramesAndCardsAsOrderedTemplateElements() {
        when(templateRepository.save(any(WhiteboardTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Frame frame = new Frame(BOARD_A, TENANT_A, 0, 0, Instant.now());
        setId(frame, UUID.randomUUID());
        Card card = new Card(BOARD_A, TENANT_A, CardType.TEXT, "Idée", 10, 10, Instant.now());
        setId(card, UUID.randomUUID());

        when(frameRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(BOARD_A, TENANT_A))
                .thenReturn(List.of(frame));
        when(cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(BOARD_A, TENANT_A))
                .thenReturn(List.of(card));
        when(boardFieldRepository.findAllByBoardIdOrderByOrderAscCreatedAtAsc(BOARD_A)).thenReturn(List.of());
        when(cardConnectionRepository.findAllByBoardIdAndTenantId(BOARD_A, TENANT_A)).thenReturn(List.of());
        when(cardFieldValueRepository.findByCardId(card.getId())).thenReturn(List.of());

        WhiteboardTemplate template =
                templateService.createFromBoard(BOARD_A, TENANT_A, "My Template", "desc");

        assertThat(template.getTenantId()).isEqualTo(TENANT_A);
        assertThat(template.getName()).isEqualTo("My Template");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhiteboardTemplateElement>> captor = ArgumentCaptor.forClass(List.class);
        verify(templateElementRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).getElementType()).isEqualTo(TemplateElementType.FRAME);
        assertThat(captor.getValue().get(0).getLocalKey()).isEqualTo(frame.getId().toString());
        assertThat(captor.getValue().get(1).getElementType()).isEqualTo(TemplateElementType.CARD);
        assertThat(captor.getValue().get(1).getLocalKey()).isEqualTo(card.getId().toString());
    }

    /**
     * Given a board with no content, when createFromBoard() is called,
     * then a valid empty template is created (no elements).
     */
    @Test
    void createFromBoard_withEmptyBoard_createsEmptyTemplate() {
        when(templateRepository.save(any(WhiteboardTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(frameRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(BOARD_A, TENANT_A))
                .thenReturn(List.of());
        when(cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(BOARD_A, TENANT_A))
                .thenReturn(List.of());
        when(boardFieldRepository.findAllByBoardIdOrderByOrderAscCreatedAtAsc(BOARD_A)).thenReturn(List.of());
        when(cardConnectionRepository.findAllByBoardIdAndTenantId(BOARD_A, TENANT_A)).thenReturn(List.of());

        WhiteboardTemplate template =
                templateService.createFromBoard(BOARD_A, TENANT_A, "Empty", null);

        assertThat(template.getName()).isEqualTo("Empty");
        verify(templateElementRepository).saveAll(eq(List.of()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private WhiteboardTemplate mockTemplate(final UUID id, final String code, final String name) {
        WhiteboardTemplate template = mock(WhiteboardTemplate.class);
        lenient().when(template.getId()).thenReturn(id);
        lenient().when(template.getCode()).thenReturn(code);
        lenient().when(template.getName()).thenReturn(name);
        return template;
    }

    private WhiteboardTemplateElement mockElement(
            final TemplateElementType type, final String localKey, final String payload) {
        WhiteboardTemplateElement element = mock(WhiteboardTemplateElement.class);
        lenient().when(element.getElementType()).thenReturn(type);
        lenient().when(element.getLocalKey()).thenReturn(localKey);
        lenient().when(element.getPayload()).thenReturn(payload);
        return element;
    }

    /** Reflectively sets a {@link Card}'s server-generated id, unreachable via any public setter. */
    private void setId(final Card card, final UUID id) {
        setPrivateField(card, "id", id);
    }

    /** Reflectively sets a {@link Frame}'s server-generated id, unreachable via any public setter. */
    private void setId(final Frame frame, final UUID id) {
        setPrivateField(frame, "id", id);
    }

    /** Reflectively sets a {@link BoardField}'s server-generated id, unreachable via any public setter. */
    private void setFieldId(final BoardField field, final UUID id) {
        setPrivateField(field, "id", id);
    }

    private void setPrivateField(final Object target, final String fieldName, final Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
