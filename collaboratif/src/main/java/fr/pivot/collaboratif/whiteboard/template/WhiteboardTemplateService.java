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
import fr.pivot.collaboratif.whiteboard.canvas.CardFieldValue;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for whiteboard templates (US08.4.1): listing the gallery, resolving a
 * {@code templateId} to a global template, and initializing a newly created board from one
 * (EN08.x re-platform onto the live board model).
 *
 * <p>In the Socle, only global public templates exist ({@code tenant_id IS NULL}) — see
 * the US's Gate 1 resolution. There is no tenant-scoped template <em>creation</em> channel
 * exposed to end users, so every lookup/resolution here is scoped to global templates only;
 * {@link #createFromBoard} is the one write path that produces a tenant-private row (US08.2.4).
 *
 * <p><strong>Re-platform rationale.</strong> The original design materialized a template's
 * elements as legacy {@code canvas_event} {@code DRAW} rows — a channel only the retired
 * freeform canvas ({@code WhiteboardBoardComponent}) ever read. Since EN08.4 the routed board
 * surface ({@code structured-canvas}) hydrates exclusively from {@code board:state}
 * ({@code {cards, connections, frames, fields}}), so every template element materialized the old
 * way was invisible on the live board. {@link #initializeBoard} now creates real
 * {@link Frame}/{@link Card}/{@link CardConnection}/{@link BoardField}/{@link CardFieldValue}
 * rows instead — the exact entities {@code board:state} serves.
 */
@Service
@Transactional(readOnly = true)
public class WhiteboardTemplateService {

    private final WhiteboardTemplateRepository templateRepository;
    private final WhiteboardTemplateElementRepository templateElementRepository;
    private final TemplateElementValidator templateElementValidator;
    private final ShapeStyleSanitizer shapeStyleSanitizer;
    private final FrameRepository frameRepository;
    private final CardRepository cardRepository;
    private final CardConnectionRepository cardConnectionRepository;
    private final BoardFieldRepository boardFieldRepository;
    private final CardFieldValueRepository cardFieldValueRepository;
    private final WhiteboardModuleCheck moduleCheck;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service with all required dependencies.
     *
     * @param templateRepository        repository for template header persistence
     * @param templateElementRepository repository for template element persistence
     * @param templateElementValidator  strict per-element-type schema validator
     * @param shapeStyleSanitizer       normalizes {@code SHAPE} card content, mirroring the
     *                                  hardening every user-drawn SHAPE card content goes through
     * @param frameRepository           repository for live {@link Frame} entities
     * @param cardRepository            repository for live {@link Card} entities
     * @param cardConnectionRepository  repository for live {@link CardConnection} entities
     * @param boardFieldRepository      repository for live {@link BoardField} entities
     * @param cardFieldValueRepository  repository for live {@link CardFieldValue} entities
     * @param moduleCheck               check for whiteboard module activation
     * @param objectMapper              Jackson mapper used to build/read template element payloads
     */
    public WhiteboardTemplateService(
            final WhiteboardTemplateRepository templateRepository,
            final WhiteboardTemplateElementRepository templateElementRepository,
            final TemplateElementValidator templateElementValidator,
            final ShapeStyleSanitizer shapeStyleSanitizer,
            final FrameRepository frameRepository,
            final CardRepository cardRepository,
            final CardConnectionRepository cardConnectionRepository,
            final BoardFieldRepository boardFieldRepository,
            final CardFieldValueRepository cardFieldValueRepository,
            final WhiteboardModuleCheck moduleCheck,
            final ObjectMapper objectMapper) {
        this.templateRepository = templateRepository;
        this.templateElementRepository = templateElementRepository;
        this.templateElementValidator = templateElementValidator;
        this.shapeStyleSanitizer = shapeStyleSanitizer;
        this.frameRepository = frameRepository;
        this.cardRepository = cardRepository;
        this.cardConnectionRepository = cardConnectionRepository;
        this.boardFieldRepository = boardFieldRepository;
        this.cardFieldValueRepository = cardFieldValueRepository;
        this.moduleCheck = moduleCheck;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists all global templates available in the gallery, ordered for display.
     *
     * @param tenantId the calling tenant's {@code public.tenants.id} (used only for the
     *                 module-activation check)
     * @return the ordered list of template responses
     * @throws WhiteboardModuleDisabledException if the whiteboard module is inactive for the tenant
     */
    public List<TemplateResponse> listGlobalTemplates(final Long tenantId) {
        if (!moduleCheck.isEnabled(tenantId)) {
            throw new WhiteboardModuleDisabledException(tenantId);
        }
        return templateRepository.findAllByTenantIdIsNullOrderByDisplayOrderAsc().stream()
                .map(TemplateResponse::from)
                .toList();
    }

    /**
     * Resolves a raw {@code templateId} request parameter to an existing global template.
     *
     * @param rawTemplateId the raw, caller-supplied {@code templateId} string
     * @return the resolved global template
     * @throws InvalidTemplateIdException if the value is not a syntactically valid UUID
     * @throws TemplateNotFoundException  if no global template exists for that UUID
     */
    public WhiteboardTemplate resolveGlobalTemplate(final String rawTemplateId) {
        UUID templateId;
        try {
            templateId = UUID.fromString(rawTemplateId);
        } catch (IllegalArgumentException e) {
            throw new InvalidTemplateIdException(rawTemplateId);
        }
        return templateRepository.findByIdAndTenantIdIsNull(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    /**
     * Initializes a newly created board from a template's elements, materializing each one onto
     * the live board model.
     *
     * <p>Every element is re-validated against {@link TemplateElementValidator} before
     * materialization — an internal invariant check on seed data, not a caller input
     * validation (the caller only ever supplies {@code templateId}). Processed in two passes:
     * {@code FRAME}/{@code CARD}/{@code FIELD} elements first (each remembering its generated
     * UUID under its {@code localKey}, if any), then {@code CONNECTION}/{@code FIELD_VALUE}
     * elements, which resolve their {@code *Key} payload fields against that map — this passing
     * order is independent of a template's {@code displayOrder} (a connection may be authored
     * before the cards it links).
     *
     * @param template the already-resolved global template (see {@link #resolveGlobalTemplate})
     * @param boardId  the newly created board's UUID
     * @param tenantId the owning tenant's {@code public.tenants.id}
     * @param userId   the board creator's {@code public.users.id} (unused by materialization
     *                 itself; kept for API stability with the {@code BoardService.create} call
     *                 site and potential future audit logging)
     * @throws InvalidCanvasElementException if an element's payload references an unknown
     *                                        {@code localKey} or fails schema validation
     */
    @Transactional
    public void initializeBoard(
            final WhiteboardTemplate template,
            final UUID boardId,
            final Long tenantId,
            final Long userId) {
        List<WhiteboardTemplateElement> elements =
                templateElementRepository.findAllByTemplateIdOrderByDisplayOrderAsc(template.getId());
        for (WhiteboardTemplateElement element : elements) {
            templateElementValidator.validate(element.getElementType(), element.getPayload());
        }

        Instant now = Instant.now();
        Map<String, UUID> keyToId = new HashMap<>();
        Map<String, UUID> groupKeyToId = new HashMap<>();
        List<WhiteboardTemplateElement> deferred = new ArrayList<>();

        for (WhiteboardTemplateElement element : elements) {
            JsonNode node = objectMapper.readTree(element.getPayload());
            switch (element.getElementType()) {
                case FRAME -> materializeFrame(node, boardId, tenantId, now, element.getLocalKey(), keyToId);
                case CARD -> materializeCard(node, boardId, tenantId, now, element.getLocalKey(), keyToId, groupKeyToId);
                case FIELD -> materializeField(node, boardId, tenantId, now, element.getLocalKey(), keyToId);
                case CONNECTION, FIELD_VALUE -> deferred.add(element);
            }
        }
        for (WhiteboardTemplateElement element : deferred) {
            JsonNode node = objectMapper.readTree(element.getPayload());
            if (element.getElementType() == TemplateElementType.CONNECTION) {
                materializeConnection(node, boardId, tenantId, now, keyToId);
            } else {
                materializeFieldValue(node, keyToId);
            }
        }
    }

    private void materializeFrame(
            final JsonNode node, final UUID boardId, final Long tenantId, final Instant now,
            final String localKey, final Map<String, UUID> keyToId) {
        Frame frame = new Frame(boardId, tenantId, node.get("posX").asDouble(), node.get("posY").asDouble(), now);
        frame.setTitle(node.get("title").asString());
        frame.setWidth(node.get("width").asDouble());
        frame.setHeight(node.get("height").asDouble());
        if (node.has("color")) {
            frame.setColor(node.get("color").asString());
        }
        if (node.has("layer")) {
            frame.setLayer(node.get("layer").asInt());
        }
        Frame saved = frameRepository.save(frame);
        if (localKey != null) {
            keyToId.put(localKey, saved.getId());
        }
    }

    private void materializeCard(
            final JsonNode node, final UUID boardId, final Long tenantId, final Instant now,
            final String localKey, final Map<String, UUID> keyToId, final Map<String, UUID> groupKeyToId) {
        CardType type = CardType.valueOf(node.get("type").asString());
        String rawContent = node.get("content").asString();
        String content = type == CardType.SHAPE ? shapeStyleSanitizer.sanitize(rawContent) : rawContent;
        Card card = new Card(
                boardId, tenantId, type, content,
                node.get("posX").asDouble(), node.get("posY").asDouble(), now);
        card.setWidth(node.get("width").asDouble());
        card.setHeight(node.get("height").asDouble());
        if (node.has("color")) {
            card.setColor(node.get("color").asString());
        }
        if (node.has("groupKey")) {
            UUID groupId = groupKeyToId.computeIfAbsent(node.get("groupKey").asString(), k -> UUID.randomUUID());
            card.setGroupId(groupId);
            card.setGroupColor(card.getColor());
        }
        if (node.has("layer")) {
            card.setLayer(node.get("layer").asInt());
        }
        Card saved = cardRepository.save(card);
        if (localKey != null) {
            keyToId.put(localKey, saved.getId());
        }
    }

    private void materializeField(
            final JsonNode node, final UUID boardId, final Long tenantId, final Instant now,
            final String localKey, final Map<String, UUID> keyToId) {
        FieldType type = FieldType.valueOf(node.get("type").asString());
        String emoji = node.has("emoji") ? node.get("emoji").asString() : null;
        String options = node.has("options") ? node.get("options").toString() : null;
        BoardField field = new BoardField(
                boardId, tenantId, node.get("name").asString(), emoji, type, options,
                node.get("order").asInt(), now);
        BoardField saved = boardFieldRepository.save(field);
        if (localKey != null) {
            keyToId.put(localKey, saved.getId());
        }
    }

    private void materializeConnection(
            final JsonNode node, final UUID boardId, final Long tenantId, final Instant now,
            final Map<String, UUID> keyToId) {
        UUID fromId = resolveKey(keyToId, node.get("fromKey").asString());
        UUID toId = resolveKey(keyToId, node.get("toKey").asString());
        CardConnection connection = new CardConnection(boardId, tenantId, fromId, toId, now);
        if (node.has("label")) {
            connection.setLabel(node.get("label").asString());
        }
        if (node.has("color")) {
            connection.setColor(node.get("color").asString());
        }
        connection.setShape(node.get("shape").asString());
        connection.setLineStyle(node.get("lineStyle").asString());
        connection.setStartCap(node.get("startCap").asString());
        connection.setEndCap(node.get("endCap").asString());
        if (node.has("width")) {
            connection.setWidth(node.get("width").asInt());
        }
        cardConnectionRepository.save(connection);
    }

    private void materializeFieldValue(final JsonNode node, final Map<String, UUID> keyToId) {
        UUID cardId = resolveKey(keyToId, node.get("cardKey").asString());
        UUID fieldId = resolveKey(keyToId, node.get("fieldKey").asString());
        cardFieldValueRepository.save(new CardFieldValue(cardId, fieldId, node.get("value").asString()));
    }

    private UUID resolveKey(final Map<String, UUID> keyToId, final String key) {
        UUID id = keyToId.get(key);
        if (id == null) {
            throw new InvalidCanvasElementException("Template element references unknown local key: " + key);
        }
        return id;
    }

    /**
     * Snapshots a board's current live content into a new tenant-private template (US08.2.4
     * "save as template").
     *
     * <p>Captures every {@link Frame}, {@link Card}, {@link BoardField},
     * {@link CardConnection} and {@link CardFieldValue} of the source board — the same
     * entities {@code board:state} serves — as template elements, using each captured entity's
     * own (already-generated) UUID as its {@code localKey} so {@code CONNECTION}/
     * {@code FIELD_VALUE} elements can reference their endpoints/targets directly. This
     * supersedes the previous design, which captured legacy {@code canvas_event} {@code DRAW}
     * rows — a table EN08.4 stopped populating for live board content, making that capture
     * effectively always empty.
     *
     * <p>Unlike {@link #initializeBoard}, elements produced here are <strong>not</strong>
     * validated against {@link TemplateElementValidator}: a captured {@link Card} may carry a
     * {@link CardType} (e.g. {@code IMAGE}, {@code DRAW}, {@code TABLE}, {@code LINK}) outside
     * that validator's current {@code TEXT}/{@code LABEL}/{@code SHAPE} whitelist, and rejecting
     * or silently dropping such cards would lose content the user explicitly asked to save. There
     * is still no endpoint that resolves a tenant-private template (non-null {@code tenant_id})
     * back through {@link #initializeBoard} (which only accepts templates resolved via
     * {@link #resolveGlobalTemplate}, {@code tenant_id IS NULL}) — this snapshot is captured with
     * full fidelity but is not replayable yet, a pre-existing gap this method does not close.
     *
     * @param boardId     the source board UUID (already resolved tenant-scoped and
     *                    OWNER-authorized by the caller)
     * @param tenantId    the owning tenant's {@code public.tenants.id} — the new template is
     *                    private to this tenant (non-null {@code tenant_id})
     * @param name        the template name (validated at the controller layer)
     * @param description the optional template description
     * @return the newly persisted template header
     */
    @Transactional
    public WhiteboardTemplate createFromBoard(
            final UUID boardId,
            final Long tenantId,
            final String name,
            final String description) {
        WhiteboardTemplate template = templateRepository.save(
                new WhiteboardTemplate(UUID.randomUUID(), tenantId, name, description, null));

        List<Frame> frames =
                frameRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, tenantId);
        List<Card> cards =
                cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, tenantId);
        List<BoardField> fields = boardFieldRepository.findAllByBoardIdOrderByOrderAscCreatedAtAsc(boardId);
        List<CardConnection> connections = cardConnectionRepository.findAllByBoardIdAndTenantId(boardId, tenantId);

        List<WhiteboardTemplateElement> elements = new ArrayList<>();
        int order = 0;
        for (Frame frame : frames) {
            elements.add(new WhiteboardTemplateElement(
                    UUID.randomUUID(), template.getId(), TemplateElementType.FRAME,
                    frame.getId().toString(), frameToPayload(frame), order++));
        }
        for (Card card : cards) {
            elements.add(new WhiteboardTemplateElement(
                    UUID.randomUUID(), template.getId(), TemplateElementType.CARD,
                    card.getId().toString(), cardToPayload(card), order++));
        }
        for (BoardField field : fields) {
            elements.add(new WhiteboardTemplateElement(
                    UUID.randomUUID(), template.getId(), TemplateElementType.FIELD,
                    field.getId().toString(), fieldToPayload(field), order++));
        }
        for (CardConnection connection : connections) {
            elements.add(new WhiteboardTemplateElement(
                    UUID.randomUUID(), template.getId(), TemplateElementType.CONNECTION,
                    null, connectionToPayload(connection), order++));
        }
        for (Card card : cards) {
            for (CardFieldValue value : cardFieldValueRepository.findByCardId(card.getId())) {
                elements.add(new WhiteboardTemplateElement(
                        UUID.randomUUID(), template.getId(), TemplateElementType.FIELD_VALUE,
                        null, fieldValueToPayload(card.getId(), value), order++));
            }
        }
        templateElementRepository.saveAll(elements);
        return template;
    }

    private String frameToPayload(final Frame frame) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("title", frame.getTitle());
        node.put("posX", frame.getPosX());
        node.put("posY", frame.getPosY());
        node.put("width", frame.getWidth());
        node.put("height", frame.getHeight());
        node.put("color", frame.getColor());
        node.put("layer", frame.getLayer());
        return node.toString();
    }

    private String cardToPayload(final Card card) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", card.getType().name());
        node.put("content", card.getContent());
        node.put("posX", card.getPosX());
        node.put("posY", card.getPosY());
        node.put("width", card.getWidth());
        node.put("height", card.getHeight());
        node.put("color", card.getColor());
        if (card.getGroupId() != null) {
            node.put("groupKey", card.getGroupId().toString());
        }
        node.put("layer", card.getLayer());
        return node.toString();
    }

    private String fieldToPayload(final BoardField field) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", field.getName());
        if (field.getEmoji() != null) {
            node.put("emoji", field.getEmoji());
        }
        node.put("type", field.getType().name());
        if (field.getOptions() != null) {
            node.set("options", objectMapper.readTree(field.getOptions()));
        }
        node.put("order", field.getOrder());
        return node.toString();
    }

    private String connectionToPayload(final CardConnection connection) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("fromKey", connection.getFromId().toString());
        node.put("toKey", connection.getToId().toString());
        if (connection.getLabel() != null) {
            node.put("label", connection.getLabel());
        }
        if (connection.getColor() != null) {
            node.put("color", connection.getColor());
        }
        node.put("shape", connection.getShape());
        node.put("lineStyle", connection.getLineStyle());
        node.put("startCap", connection.getStartCap());
        node.put("endCap", connection.getEndCap());
        node.put("width", connection.getWidth());
        return node.toString();
    }

    private String fieldValueToPayload(final UUID cardId, final CardFieldValue value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("cardKey", cardId.toString());
        node.put("fieldKey", value.getFieldId().toString());
        node.put("value", value.getValue());
        return node.toString();
    }
}
