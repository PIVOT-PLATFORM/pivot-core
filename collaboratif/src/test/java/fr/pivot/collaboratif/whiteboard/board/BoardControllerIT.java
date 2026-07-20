package fr.pivot.collaboratif.whiteboard.board;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.canvas.BoardField;
import fr.pivot.collaboratif.whiteboard.canvas.BoardFieldRepository;
import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEvent;
import fr.pivot.collaboratif.whiteboard.canvas.CanvasEventRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardFieldValueRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardRepository;
import fr.pivot.collaboratif.whiteboard.canvas.CardType;
import fr.pivot.collaboratif.whiteboard.canvas.Frame;
import fr.pivot.collaboratif.whiteboard.canvas.FrameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BoardController} exercising the full Spring context
 * against a real PostgreSQL database and Redis provided by Testcontainers.
 *
 * <p>Covers US08.1.1 acceptance criteria (POST /whiteboard/boards) plus CRUD operations
 * for board list, read, rename, and delete. Each test authenticates via real bearer
 * tokens issued for tenants/users seeded through {@link PlatformAuthTestSupport}
 * (EN08.3) — tenant and user isolation is exercised with distinct seeded identities.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path
 * directly, without the {@code server.servlet.context-path} prefix. Paths used in tests
 * therefore start with {@code /whiteboard/boards} (not {@code /api/collaboratif/...}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BoardControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String BASE_PATH = "/collaboratif/whiteboard/boards";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private CanvasEventRepository canvasEventRepository;

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private FrameRepository frameRepository;

    @Autowired
    private BoardFieldRepository boardFieldRepository;

    @Autowired
    private CardFieldValueRepository cardFieldValueRepository;

    private MockMvc mockMvc;

    /** UUID of the "Brainstorm" template, fixed in the Flyway seed data (US08.4.1). */
    private static final UUID BRAINSTORM_TEMPLATE_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** UUID of the "Retrospective" template, fixed in the Flyway seed data (US08.4.1). */
    private static final UUID RETROSPECTIVE_TEMPLATE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private String tokenA;
    private long tenantB;
    private String tokenB;

    /**
     * Sets up MockMvc from the web application context and seeds two distinct
     * tenant/user/token fixtures (A and B) before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        tokenA = fixtureA.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantB = fixtureB.tenantId();
        tokenB = fixtureB.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /whiteboard/boards
    // -------------------------------------------------------------------------

    /**
     * Given valid headers and a non-blank title,
     * when POST /whiteboard/boards is called,
     * then it returns HTTP 201 with id, title, role "OWNER", and tenantId.
     */
    @Test
    void createBoard_returnsCreatedWithOwnerRole() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Sprint Planning\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Sprint Planning"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.tenantId").value(tenantA));
    }

    /**
     * Given an empty title string,
     * when POST /whiteboard/boards is called,
     * then it returns HTTP 400 with code "INVALID_TITLE".
     */
    @Test
    void createBoard_withEmptyTitle_returns400WithInvalidTitleCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    /**
     * Given the Authorization bearer header is absent,
     * when POST /whiteboard/boards is called,
     * then it returns HTTP 401 Unauthorized.
     */
    @Test
    void createBoard_missingPrincipalHeaders_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Board\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /whiteboard/boards?templateId=... (US08.4.1)
    // -------------------------------------------------------------------------

    /**
     * Given a valid templateId resolving to the "Brainstorm" global template, when
     * POST /whiteboard/boards?templateId=... is called, then it returns HTTP 201 and the new
     * board is materialized as real, live-model rows (4 cards, 3 frames) — the exact entities
     * {@code board:state} serves to the routed board surface (EN08.x re-platform regression
     * guard: the pre-re-platform design instead wrote legacy {@code canvas_event} DRAW rows the
     * live board never reads, so this assertion on {@link CardRepository}/{@link
     * FrameRepository} — not {@link CanvasEventRepository} — is the point of the test).
     */
    @Test
    void createBoard_withValidTemplateId_returns201AndMaterializesLiveCardsAndFrames() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .queryParam("templateId", BRAINSTORM_TEMPLATE_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"From Brainstorm\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("From Brainstorm"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID boardId = UUID.fromString(body.get("id").asText());

        List<Card> cards = cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, tenantA);
        List<Frame> frames =
                frameRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, tenantA);

        assertThat(cards).hasSize(4);
        assertThat(cards).allSatisfy(card -> {
            assertThat(card.getBoardId()).isEqualTo(boardId);
            assertThat(card.getTenantId()).isEqualTo(tenantA);
            assertThat(card.getContent()).isNotBlank();
        });
        assertThat(frames).hasSize(3);
        assertThat(frames).extracting(Frame::getTitle)
                .containsExactlyInAnyOrder("Idées", "Regrouper", "Top idées");

        assertThat(canvasEventRepository.findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(boardId, tenantA))
                .as("the re-platformed template engine no longer writes to the legacy canvas_event table")
                .isEmpty();
    }

    /**
     * Given a valid templateId resolving to the "Retrospective" global template, when
     * POST /whiteboard/boards?templateId=... is called, then the new board is materialized
     * with 4 cards, 3 frames, 2 board fields, and 1 field value (US08.10 custom fields
     * demonstrated by the enriched Retrospective template).
     */
    @Test
    void createBoard_withRetrospectiveTemplateId_materializesCardsFramesAndFields() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .queryParam("templateId", RETROSPECTIVE_TEMPLATE_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"From Retro\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID boardId = UUID.fromString(body.get("id").asText());

        List<Card> cards = cardRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, tenantA);
        List<Frame> frames =
                frameRepository.findAllByBoardIdAndTenantIdOrderByLayerAscCreatedAtAsc(boardId, tenantA);
        List<BoardField> fields = boardFieldRepository.findAllByBoardIdOrderByOrderAscCreatedAtAsc(boardId);

        assertThat(cards).hasSize(4);
        assertThat(frames).hasSize(3);
        assertThat(frames).extracting(Frame::getTitle)
                .containsExactlyInAnyOrder("Bien 🙂", "À améliorer 😕", "Actions ✅");
        assertThat(fields).extracting(BoardField::getName)
                .containsExactlyInAnyOrder("Responsable", "Échéance");

        Card actionCard = cards.stream()
                .filter(c -> c.getType() == CardType.TEXT && c.getContent().startsWith("Exemple : fixer"))
                .findFirst()
                .orElseThrow();
        BoardField responsableField = fields.stream()
                .filter(f -> "Responsable".equals(f.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(cardFieldValueRepository.findByCardId(actionCard.getId()))
                .anySatisfy(value -> {
                    assertThat(value.getFieldId()).isEqualTo(responsableField.getId());
                    assertThat(value.getValue()).isEqualTo("À définir");
                });
    }

    /**
     * Given no templateId query parameter, when POST /whiteboard/boards is called,
     * then the board is created blank with no canvas events (US08.1.1 behaviour
     * unchanged).
     */
    @Test
    void createBoard_withoutTemplateId_hasNoCanvasEvents() throws Exception {
        String boardId = createBoardFor(tokenA, "Blank Board");

        List<CanvasEvent> events = canvasEventRepository.findAllByBoardIdAndTenantIdOrderByCreatedAtAsc(
                UUID.fromString(boardId), tenantA);

        assertThat(events).isEmpty();
    }

    /**
     * Error case: given a templateId that is not a syntactically valid UUID,
     * when POST /whiteboard/boards?templateId=... is called,
     * then it returns HTTP 400 with code "INVALID_TEMPLATE_ID".
     */
    @Test
    void createBoard_withMalformedTemplateId_returns400WithInvalidTemplateIdCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .queryParam("templateId", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Board\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TEMPLATE_ID"));
    }

    /**
     * Error case: given a templateId with a well-formed UUID that does not match any
     * template, when POST /whiteboard/boards?templateId=... is called,
     * then it returns HTTP 404 (no existence leak) and no board is persisted.
     */
    @Test
    void createBoard_withNonExistentTemplateId_returns404() throws Exception {
        UUID unknownTemplateId = UUID.randomUUID();

        mockMvc.perform(post(BASE_PATH)
                        .queryParam("templateId", unknownTemplateId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Board\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /whiteboard/boards — extended creation contract (US08.1.9)
    // -------------------------------------------------------------------------

    /**
     * Given a creation request with {@code maxParticipants}/{@code enabledActivities}/
     * {@code coverImage}, when POST /whiteboard/boards is called, then all three are persisted
     * and echoed back in the 201 response (US08.1.9 AC).
     */
    @Test
    void ac08_1_9_19_createBoard_withSettingsFields_persistsAndReturnsThem() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Full Board\", \"maxParticipants\": 12, "
                                + "\"enabledActivities\": [\"VOTE\", \"TIMER\"], "
                                + "\"coverImage\": \"cover.png\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxParticipants").value(12))
                .andExpect(jsonPath("$.enabledActivities[0]").value("VOTE"))
                .andExpect(jsonPath("$.enabledActivities[1]").value("TIMER"))
                .andExpect(jsonPath("$.coverImage").value("cover.png"))
                .andExpect(jsonPath("$.shareCount").value(0));
    }

    /**
     * Error case: given {@code maxParticipants} is zero (not strictly positive), when POST
     * /whiteboard/boards is called, then it returns HTTP 400 before any board is persisted.
     */
    @Test
    void ac08_1_9_20_createBoard_withZeroMaxParticipants_returns400() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Board\", \"maxParticipants\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MAX_PARTICIPANTS"));
    }

    /**
     * Error case: given {@code maxParticipants} is negative, when POST /whiteboard/boards is
     * called, then it returns HTTP 400.
     */
    @Test
    void ac08_1_9_21_createBoard_withNegativeMaxParticipants_returns400() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Board\", \"maxParticipants\": -3}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Error case: given {@code enabledActivities} contains a code outside the known whitelist
     * (the reachable form of "invalid element" at the service layer — a JSON element of the
     * wrong type is coerced to string by Jackson and then rejected by the same whitelist check),
     * when POST /whiteboard/boards is called, then it returns HTTP 400 with code
     * "INVALID_ACTIVITY" and no board is persisted.
     */
    @Test
    void ac08_1_9_22_createBoard_withUnknownActivity_returns400AndPersistsNothing() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Board\", \"enabledActivities\": [\"NOT_REAL\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACTIVITY"));

        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * Error case: given {@code enabledActivities} contains a JSON element that is not a
     * string (a number), when POST /whiteboard/boards is called, then it returns HTTP 400 —
     * Jackson coerces the numeric literal to its string form, which then fails the same
     * whitelist check (there is no activity code {@code "123"}), giving the same observable
     * 400 outcome as a genuinely unknown activity string.
     */
    @Test
    void ac08_1_9_22b_createBoard_withNonStringActivityElement_returns400() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Board\", \"enabledActivities\": [123]}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given no settings fields in the creation request (title only), when POST
     * /whiteboard/boards is called, then the board is created with the pre-US08.1.9 defaults
     * (contract unchanged for callers that only send a title).
     */
    @Test
    void ac08_1_9_23_createBoard_titleOnly_hasNullSettingsAndZeroShareCount() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Plain Board\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxParticipants").doesNotExist())
                .andExpect(jsonPath("$.enabledActivities").isEmpty())
                .andExpect(jsonPath("$.coverImage").doesNotExist())
                .andExpect(jsonPath("$.shareCount").value(0));
    }

    // -------------------------------------------------------------------------
    // GET /whiteboard/boards
    // -------------------------------------------------------------------------

    /**
     * Given two boards owned by the same user in the same tenant,
     * when GET /whiteboard/boards is called,
     * then it returns HTTP 200 with a non-empty boards array.
     */
    @Test
    void listBoards_returnsOwnedBoards() throws Exception {
        createBoardFor(tokenA, "Board A1");
        createBoardFor(tokenA, "Board A2");

        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boards").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    /**
     * Given boards belonging to two different tenants,
     * when user B lists boards for tenant B,
     * then all returned boards have tenantId equal to tenant B (tenant isolation).
     */
    @Test
    void listBoards_tenantIsolation_userBSeesOnlyOwnBoards() throws Exception {
        createBoardFor(tokenA, "Tenant A Board");
        createBoardFor(tokenB, "Tenant B Board");

        MvcResult result = mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        body.get("boards").forEach(board ->
                assertThat(board.get("tenantId").asLong()).isEqualTo(tenantB));
    }

    /**
     * Given a negative page size parameter,
     * when GET /whiteboard/boards is called,
     * then it returns HTTP 400.
     */
    @Test
    void listBoards_withNegativeSize_returns400() throws Exception {
        mockMvc.perform(get(BASE_PATH)
                        .param("size", "-1")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    /**
     * Given a board shared with one EDITOR, when GET /whiteboard/boards is called by the
     * owner, then the board's {@code shareCount} is 1 — one active share, the owner's own
     * membership row excluded (US08.1.9 AC).
     */
    @Test
    void ac08_1_9_27_listBoards_reflectsShareCountExcludingOwner() throws Exception {
        String boardId = createBoardFor(tokenA, "Shared Board");
        long editorId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        insertBoardMember(UUID.fromString(boardId), editorId, BoardRole.EDITOR);

        MvcResult result = mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode found = null;
        for (JsonNode b : body.get("boards")) {
            if (b.get("id").asString().equals(boardId)) {
                found = b;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.get("shareCount").asInt()).isEqualTo(1);
    }

    /**
     * Given a board with no shares, when GET /whiteboard/boards is called, then {@code
     * shareCount} is 0.
     */
    @Test
    void ac08_1_9_28_listBoards_unsharedBoard_hasZeroShareCount() throws Exception {
        createBoardFor(tokenA, "Solo Board");

        mockMvc.perform(get(BASE_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boards[0].shareCount").value(0));
    }

    // -------------------------------------------------------------------------
    // GET /whiteboard/boards/{boardId}
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner,
     * when GET /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 200 with the board id and title.
     */
    @Test
    void findById_whenOwner_returnsBoard() throws Exception {
        String boardId = createBoardFor(tokenA, "My Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(boardId))
                .andExpect(jsonPath("$.title").value("My Board"));
    }

    /**
     * Given a board with a persisted card carrying opaque metadata, when GET
     * /whiteboard/boards/{boardId} is called, then the response embeds the card with its
     * {@code fieldValues} (US08.1.9 AC) and the caller's {@code role}.
     */
    @Test
    void ac08_1_9_24_findById_includesCardsWithFieldValuesAndRole() throws Exception {
        String boardId = createBoardFor(tokenA, "Board With Cards");
        fr.pivot.collaboratif.whiteboard.canvas.Card card = new fr.pivot.collaboratif.whiteboard.canvas.Card(
                UUID.fromString(boardId), tenantA,
                fr.pivot.collaboratif.whiteboard.canvas.CardType.LINK, "https://example.com",
                10, 20, Instant.now());
        card.setMeta("{\"title\":\"Example\"}");
        cardRepository.save(card);

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.cards", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.cards[0].content").value("https://example.com"))
                .andExpect(jsonPath("$.cards[0].fieldValues.title").value("Example"))
                .andExpect(jsonPath("$.frames").doesNotExist())
                .andExpect(jsonPath("$.connections").doesNotExist())
                .andExpect(jsonPath("$.fields").doesNotExist());
    }

    /**
     * Given a board with no cards, when GET /whiteboard/boards/{boardId} is called, then the
     * response contains an empty {@code cards} array (never {@code null}).
     */
    @Test
    void ac08_1_9_25_findById_noCards_returnsEmptyCardsArray() throws Exception {
        String boardId = createBoardFor(tokenA, "Empty Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards").isEmpty());
    }

    /**
     * Given a board shared with an EDITOR, when GET /whiteboard/boards/{boardId} is called
     * by that editor, then the response's {@code role} reflects the editor's own role (not
     * the owner's), alongside the board's cards.
     */
    @Test
    void ac08_1_9_26_findById_asEditor_returnsCardsAndEditorRole() throws Exception {
        String boardId = createBoardFor(tokenA, "Shared With Cards");
        fr.pivot.collaboratif.whiteboard.canvas.Card card = new fr.pivot.collaboratif.whiteboard.canvas.Card(
                UUID.fromString(boardId), tenantA,
                fr.pivot.collaboratif.whiteboard.canvas.CardType.TEXT, "Note", 0, 0, Instant.now());
        cardRepository.save(card);
        long editorId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        String editorToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                editorId, "active", Instant.now().plusSeconds(3600));
        insertBoardMember(UUID.fromString(boardId), editorId, BoardRole.EDITOR);

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + editorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EDITOR"))
                .andExpect(jsonPath("$.cards", org.hamcrest.Matchers.hasSize(1)));
    }

    /**
     * Given a board belongs to tenant A, when a user from tenant B tries to access it,
     * then it returns HTTP 404 (cross-tenant isolation).
     */
    @Test
    void findById_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Tenant A Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /**
     * Given the caller is not a member of the board,
     * when GET /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 404 (to avoid information disclosure).
     */
    @Test
    void findById_nonMember_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Private Board");
        long strangerId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        String strangerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                strangerId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /whiteboard/boards/{boardId}/preview
    // -------------------------------------------------------------------------

    /**
     * Given a board with one card and one frame, when GET
     * /whiteboard/boards/{boardId}/preview is called, then it returns HTTP 200 with the card's
     * and frame's geometry and colour, and the card's {@code content} field is never present in
     * the JSON response (no base64 image data leaks into the preview).
     */
    @Test
    void preview_returnsCardsAndFramesGeometryOnlyWithoutContent() throws Exception {
        String boardId = createBoardFor(tokenA, "Preview Board");
        Card card = new Card(
                UUID.fromString(boardId), tenantA, CardType.IMAGE,
                "data:image/png;base64,AAAAVERYLONGBASE64PAYLOAD", 10, 20, Instant.now());
        cardRepository.save(card);
        Frame frame = new Frame(UUID.fromString(boardId), tenantA, 0, 0, Instant.now());
        frameRepository.save(frame);

        mockMvc.perform(get(BASE_PATH + "/" + boardId + "/preview")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.cards[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.cards[0].posX").value(10))
                .andExpect(jsonPath("$.cards[0].posY").value(20))
                .andExpect(jsonPath("$.cards[0].color").value("#FFEB3B"))
                .andExpect(jsonPath("$.cards[0].content").doesNotExist())
                .andExpect(jsonPath("$.frames", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.frames[0].width").value(400))
                .andExpect(jsonPath("$.frames[0].height").value(300))
                .andExpect(jsonPath("$.frames[0].color").value("#94A3B8"));
    }

    /**
     * Given a board shared with a VIEWER, when GET /whiteboard/boards/{boardId}/preview is
     * called by that viewer, then it returns HTTP 200 — the preview endpoint grants access to
     * any board member (OWNER/EDITOR/VIEWER), the same membership gate as findById.
     */
    @Test
    void preview_asViewer_returns200() throws Exception {
        String boardId = createBoardFor(tokenA, "Shared Board");
        long viewerId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        String viewerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                viewerId, "active", Instant.now().plusSeconds(3600));
        insertBoardMember(UUID.fromString(boardId), viewerId, BoardRole.VIEWER);

        mockMvc.perform(get(BASE_PATH + "/" + boardId + "/preview")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards").isEmpty())
                .andExpect(jsonPath("$.frames").isEmpty());
    }

    /**
     * Given no board exists with the given id, when GET /whiteboard/boards/{boardId}/preview
     * is called, then it returns HTTP 404.
     */
    @Test
    void preview_nonExistentBoard_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/" + UUID.randomUUID() + "/preview")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a board belongs to tenant A, when a user from tenant B requests its preview,
     * then it returns HTTP 404 (cross-tenant isolation).
     */
    @Test
    void preview_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Tenant A Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId + "/preview")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /**
     * Given the caller is in the same tenant but is not a member of the board, when GET
     * /whiteboard/boards/{boardId}/preview is called, then it returns HTTP 404 — the preview is
     * membership-gated like findById and must not leak a non-member's board geometry.
     */
    @Test
    void preview_nonMember_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Private Board");
        long strangerId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        String strangerToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                strangerId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(get(BASE_PATH + "/" + boardId + "/preview")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
    }

    /**
     * Given the Authorization bearer header is absent, when GET
     * /whiteboard/boards/{boardId}/preview is called, then it returns HTTP 401 Unauthorized.
     */
    @Test
    void preview_missingPrincipalHeaders_returns401() throws Exception {
        String boardId = createBoardFor(tokenA, "Board");

        mockMvc.perform(get(BASE_PATH + "/" + boardId + "/preview"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // PATCH /whiteboard/boards/{boardId}
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner and the new title is valid,
     * when PATCH /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 200 with the updated title.
     */
    @Test
    void renameBoard_whenOwner_returns200WithUpdatedTitle() throws Exception {
        String boardId = createBoardFor(tokenA, "Old Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"New Title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"));
    }

    /**
     * Given a board belongs to tenant A, when a user from tenant B tries to rename it,
     * then it returns HTTP 404 (cross-tenant isolation).
     */
    @Test
    void renameBoard_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"title\": \"Hacked\"}"))
                .andExpect(status().isNotFound());
    }

    /**
     * Given an empty title in the rename request,
     * when PATCH /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 400.
     */
    @Test
    void renameBoard_withEmptyTitle_returns400() throws Exception {
        String boardId = createBoardFor(tokenA, "Title");

        mockMvc.perform(patch(BASE_PATH + "/" + boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // DELETE /whiteboard/boards/{boardId}
    // -------------------------------------------------------------------------

    /**
     * Given the caller is the board owner,
     * when DELETE /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 204 and the board is no longer accessible.
     */
    @Test
    void deleteBoard_whenOwner_returns204AndBoardIsGone() throws Exception {
        String boardId = createBoardFor(tokenA, "To Delete");

        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    /**
     * Given a board belongs to tenant A, when a user from tenant B tries to delete it,
     * then it returns HTTP 404 (cross-tenant isolation).
     */
    @Test
    void deleteBoard_crossTenant_returns404() throws Exception {
        String boardId = createBoardFor(tokenA, "Title");

        mockMvc.perform(delete(BASE_PATH + "/" + boardId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /**
     * Given no board exists with the given id in the caller's tenant,
     * when DELETE /whiteboard/boards/{boardId} is called,
     * then it returns HTTP 404.
     */
    @Test
    void deleteBoard_nonExistent_returns404() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Creates a board via the API using the given caller's bearer token and returns its
     * identifier.
     *
     * @param token the caller's raw bearer token
     * @param title the board title
     * @return the string representation of the created board's UUID
     * @throws Exception if the HTTP request fails or the response status is not 201
     */
    private String createBoardFor(final String token, final String title) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"title\": \"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    /**
     * Inserts a board_member row directly, bypassing the join/share flow (not under test here)
     * — used by the US08.1.9 {@code findById} tests that need a non-owner member.
     *
     * @param boardId the board UUID
     * @param userId  the member's user id
     * @param role    the member's role
     * @throws Exception if the insert fails
     */
    private void insertBoardMember(final UUID boardId, final long userId, final BoardRole role)
            throws Exception {
        String sql = "INSERT INTO collaboratif.board_member (board_id, user_id, role, joined_at) "
                + "VALUES (?, ?, ?, now())";
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, boardId);
            ps.setLong(2, userId);
            ps.setString(3, role.name());
            ps.executeUpdate();
        }
    }
}
