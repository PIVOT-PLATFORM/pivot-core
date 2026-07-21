package fr.pivot.collaboratif.whiteboard.template;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.collaboratif.whiteboard.board.WhiteboardModuleCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the personal-template draft lifecycle (US08.13.2) exposed by
 * {@link WhiteboardTemplateController}, exercising the full Spring context against a real
 * PostgreSQL database (Testcontainers) — create, edit-content (draft open/reuse), save-from-draft
 * and discard-draft, plus the two properties {@link TemplateDraftServiceTest} cannot verify
 * end-to-end with mocks alone: that the draft board never leaks into {@code GET
 * /whiteboard/boards}, and that a template's owner-only writes answer 404 (not 403) to a
 * different user of the same tenant.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix — paths used here start with
 * {@code /collaboratif/whiteboard/...}, not {@code /api/collaboratif/...}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WhiteboardTemplateDraftControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String TEMPLATES_PATH = "/collaboratif/whiteboard/templates";
    private static final String BOARDS_PATH = "/collaboratif/whiteboard/boards";

    @Autowired
    private WebApplicationContext wac;

    @MockitoBean
    private WhiteboardModuleCheck moduleCheck;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private String tokenA;

    /**
     * Sets up MockMvc from the web application context, seeds a real tenant/user/token fixture
     * (A) via {@link PlatformAuthTestSupport} (EN08.3), and stubs the module-activation check to
     * "enabled" — the draft lifecycle endpoints themselves do not gate on it, but
     * {@code GET /whiteboard/templates} does via the global-template listing.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        when(moduleCheck.isEnabled(any())).thenReturn(true);

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA.tenantId();
        tokenA = fixtureA.rawToken();
    }

    // -------------------------------------------------------------------------
    // create -> edit-content -> save-from-draft, end to end
    // -------------------------------------------------------------------------

    /**
     * Given a freshly created personal template, when its content is opened for editing and then
     * saved, then: (1) edit-content opens a new draft board ({@code created=true}); (2) that draft
     * is invisible in {@code GET /whiteboard/boards} while open; (3) save-from-draft captures the
     * draft's content into the template, strips the {@code "[Template] "} title prefix back to the
     * template's own name, and deletes the draft so it no longer appears anywhere, including a
     * second edit-content call now opening a brand new draft rather than reusing the deleted one.
     */
    @Test
    void fullDraftCycle_createEditContentSaveFromDraft_capturesAndCleansUpDraft() throws Exception {
        MvcResult createResult = mockMvc.perform(post(TEMPLATES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"My Template\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Template"))
                .andExpect(jsonPath("$.personal").value(true))
                .andReturn();
        UUID templateId = extractUuid(createResult, "id");

        MvcResult editResult = mockMvc.perform(post(TEMPLATES_PATH + "/" + templateId + "/edit-content")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andReturn();
        UUID draftBoardId = extractUuid(editResult, "boardId");

        // The draft is a real board, but must never leak into the ordinary board list.
        mockMvc.perform(get(BOARDS_PATH)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boards.length()").value(0));

        // Re-opening edit-content while the draft is still open reuses it, not a new one.
        mockMvc.perform(post(TEMPLATES_PATH + "/" + templateId + "/edit-content")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.boardId").value(draftBoardId.toString()));

        mockMvc.perform(post(TEMPLATES_PATH + "/" + templateId + "/save-from-draft")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("My Template"))
                .andExpect(jsonPath("$.personal").value(true));

        // The draft board no longer exists at all once saved.
        mockMvc.perform(get(BOARDS_PATH + "/" + draftBoardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        // The template survives, listed among the caller's personal templates.
        JsonNode savedTemplate = findTemplateById(tokenA, templateId);
        assertThat(savedTemplate.get("personal").asBoolean()).isTrue();
        assertThat(savedTemplate.get("name").asText()).isEqualTo("My Template");

        // A later edit-content call opens a brand new draft — the previous one is gone, not reused.
        MvcResult secondEditResult = mockMvc.perform(
                        post(TEMPLATES_PATH + "/" + templateId + "/edit-content")
                                .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true))
                .andReturn();
        assertThat(extractUuid(secondEditResult, "boardId")).isNotEqualTo(draftBoardId);
    }

    /**
     * Given an open draft, when discard-draft is called, then the draft board is deleted and the
     * template itself is left completely unchanged.
     */
    @Test
    void discardDraft_deletesDraftBoardAndLeavesTemplateUnchanged() throws Exception {
        MvcResult createResult = mockMvc.perform(post(TEMPLATES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Discard Me\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID templateId = extractUuid(createResult, "id");

        MvcResult editResult = mockMvc.perform(post(TEMPLATES_PATH + "/" + templateId + "/edit-content")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn();
        UUID draftBoardId = extractUuid(editResult, "boardId");

        mockMvc.perform(post(TEMPLATES_PATH + "/" + templateId + "/discard-draft")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BOARDS_PATH + "/" + draftBoardId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
        assertThat(findTemplateById(tokenA, templateId).get("name").asText()).isEqualTo("Discard Me");

        // Idempotent: discarding again when nothing is open is a no-op, not an error.
        mockMvc.perform(post(TEMPLATES_PATH + "/" + templateId + "/discard-draft")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------------------
    // Cross-user isolation — same tenant, different owner
    // -------------------------------------------------------------------------

    /**
     * Given a personal template owned by user A, when a different user of the <strong>same</strong>
     * tenant calls edit-content, update or delete on it, then every one of those write operations
     * answers HTTP 404 (never 403) — ownership, not tenant membership, gates a personal template,
     * and the 404 (rather than a forbidden) avoids confirming the template's existence to a caller
     * who does not own it.
     */
    @Test
    void ownerOnlyWrites_calledByDifferentUserOfSameTenant_return404() throws Exception {
        MvcResult createResult = mockMvc.perform(post(TEMPLATES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Owned by A\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID templateId = extractUuid(createResult, "id");

        long userB = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        String tokenB = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userB, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(post(TEMPLATES_PATH + "/" + templateId + "/edit-content")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch(TEMPLATES_PATH + "/" + templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"name\": \"Hijacked\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(TEMPLATES_PATH + "/" + templateId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // Untouched: the template owner (A) can still open it for editing.
        mockMvc.perform(post(TEMPLATES_PATH + "/" + templateId + "/edit-content")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID extractUuid(final MvcResult result, final String field) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get(field).asText());
    }

    /** Fetches the caller's template gallery and returns the entry matching {@code templateId}. */
    private JsonNode findTemplateById(final String token, final UUID templateId) throws Exception {
        MvcResult result = mockMvc.perform(get(TEMPLATES_PATH)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode gallery = objectMapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode entry : gallery) {
            if (templateId.toString().equals(entry.get("id").asText())) {
                return entry;
            }
        }
        throw new AssertionError("Template " + templateId + " not found in gallery: " + gallery);
    }
}
