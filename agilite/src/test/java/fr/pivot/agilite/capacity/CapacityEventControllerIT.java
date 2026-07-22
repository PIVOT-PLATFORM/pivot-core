package fr.pivot.agilite.capacity;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacityEventController} exercising the full Spring context
 * against a real PostgreSQL database (US11.1.1/US11.1.2/US11.3.1).
 *
 * <p>Covers event create (roster auto-seed, PI_PLANNING roster exclusion, parent
 * type/depth validation), get/list/update/delete (incl. children-blocks-delete 409), the
 * hierarchy children endpoint, the provisional capacity summary (leaf + PI aggregation), and
 * cross-tenant/access-denial 404 isolation.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix — paths start with {@code
 * /agilite/capacity/events}, not {@code /api/agilite/capacity/events}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityEventControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String BASE_PATH = "/agilite/capacity/events";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long teamA1;
    private String tokenA1;

    private String tokenA2;

    private long tenantB;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA1 = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA1.tenantId();
        teamA1 = fixtureA1.teamId();
        tokenA1 = fixtureA1.rawToken();

        long userA2 = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        tokenA2 = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), userA2, "active",
                java.time.Instant.now().plusSeconds(3600));

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantB = fixtureB.tenantId();
        tokenB = fixtureB.rawToken();
    }

    private String eventBody(final String type, final String name, final long teamId, final String start, final String end) {
        return "{\"type\": \"" + type + "\", \"name\": \"" + name + "\", \"teamId\": " + teamId
                + ", \"startDate\": \"" + start + "\", \"endDate\": \"" + end + "\"}";
    }

    private String createEventId(final String token, final String type, final long teamId) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(eventBody(type, "Sprint 1", teamId, "2026-01-05", "2026-01-16")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    // -------------------------------------------------------------------------
    // POST /capacity/events
    // -------------------------------------------------------------------------

    @Test
    void create_sprintEvent_autoSeedsRosterFromTeam() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(eventBody("SPRINT", "Sprint 1", teamA1, "2026-01-05", "2026-01-16")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SPRINT"))
                .andExpect(jsonPath("$.name").value("Sprint 1"));

        String eventId = createEventId(tokenA1, "SPRINT", teamA1);
        mockMvc.perform(get(BASE_PATH + "/" + eventId + "/members").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].availabilityPercent").value(100))
                .andExpect(jsonPath("$[0].excluded").value(false));
    }

    @Test
    void create_piPlanningEvent_seedsNoMembers() throws Exception {
        String eventId = createEventId(tokenA1, "PI_PLANNING", teamA1);

        mockMvc.perform(get(BASE_PATH + "/" + eventId + "/members").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_blankName_returns400WithInvalidNameCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(eventBody("SPRINT", "", teamA1, "2026-01-05", "2026-01-16")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));
    }

    @Test
    void create_startAfterEnd_returns400WithInvalidDateRangeCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(eventBody("SPRINT", "Sprint 1", teamA1, "2026-01-16", "2026-01-05")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void create_teamIdCrossTenant_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content(eventBody("SPRINT", "Sprint 1", teamA1, "2026-01-05", "2026-01-16")))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_withParentNotPiPlanning_returns400WithInvalidParentEventCode() throws Exception {
        String sprintId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"type\": \"SPRINT\", \"name\": \"Sprint 2\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"2026-02-01\", \"endDate\": \"2026-02-14\", \"parentEventId\": \""
                                + sprintId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARENT_EVENT"));
    }

    @Test
    void create_withParentAlreadyHavingAParent_returns400WithMaxDepthExceededCode() throws Exception {
        String piId = createEventId(tokenA1, "PI_PLANNING", teamA1);
        MvcResult sprintResult = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\", \"parentEventId\": \""
                                + piId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String sprintId = objectMapper.readTree(sprintResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"type\": \"SPRINT\", \"name\": \"Sprint 2\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"2026-02-01\", \"endDate\": \"2026-02-14\", \"parentEventId\": \""
                                + sprintId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MAX_DEPTH_EXCEEDED"));
    }

    // -------------------------------------------------------------------------
    // GET /capacity/events/{id}, GET /capacity/events, DELETE, PATCH
    // -------------------------------------------------------------------------

    @Test
    void findById_crossTenant_returns404() throws Exception {
        String eventId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(get(BASE_PATH + "/" + eventId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void findById_unrelatedSameTenantUser_returns404() throws Exception {
        String eventId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(get(BASE_PATH + "/" + eventId).header("Authorization", "Bearer " + tokenA2))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_filtersByTeamIdAndType() throws Exception {
        createEventId(tokenA1, "SPRINT", teamA1);
        createEventId(tokenA1, "PI_PLANNING", teamA1);

        mockMvc.perform(get(BASE_PATH + "?teamId=" + teamA1 + "&type=SPRINT")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("SPRINT"));
    }

    @Test
    void update_name_returns200() throws Exception {
        String eventId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(patch(BASE_PATH + "/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"Sprint 1 renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sprint 1 renamed"));
    }

    @Test
    void update_crossTenant_returns404() throws Exception {
        String eventId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(patch(BASE_PATH + "/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"name\": \"hijacked\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asCreator_returns204() throws Exception {
        String eventId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(delete(BASE_PATH + "/" + eventId).header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_crossTenant_returns404() throws Exception {
        String eventId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(delete(BASE_PATH + "/" + eventId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_eventWithChildren_returns409() throws Exception {
        String piId = createEventId(tokenA1, "PI_PLANNING", teamA1);
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\", \"parentEventId\": \""
                                + piId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete(BASE_PATH + "/" + piId).header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_HAS_CHILDREN"));
    }

    // -------------------------------------------------------------------------
    // GET /capacity/events/{piId}/children, GET /capacity/events/{id}/summary
    // -------------------------------------------------------------------------

    @Test
    void children_returnsSprintsOfPi() throws Exception {
        String piId = createEventId(tokenA1, "PI_PLANNING", teamA1);
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\", \"parentEventId\": \""
                                + piId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE_PATH + "/" + piId + "/children").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Sprint 1"));
    }

    @Test
    void children_crossTenant_returns404() throws Exception {
        String piId = createEventId(tokenA1, "PI_PLANNING", teamA1);

        mockMvc.perform(get(BASE_PATH + "/" + piId + "/children").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void summary_leafEvent_isProvisionalTrue() throws Exception {
        // Mon 2026-01-05 .. Fri 2026-01-16: 10 working days, one auto-seeded member at 100%.
        String eventId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(get(BASE_PATH + "/" + eventId + "/summary").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProvisional").value(true))
                .andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.workingDays").value(10))
                .andExpect(jsonPath("$.netCapacityDays").value(10.0));
    }

    @Test
    void summary_piPlanningWithNoChildren_returnsZeroCapacityNotError() throws Exception {
        String piId = createEventId(tokenA1, "PI_PLANNING", teamA1);

        mockMvc.perform(get(BASE_PATH + "/" + piId + "/summary").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netCapacityDays").value(0))
                .andExpect(jsonPath("$.netCapacityPoints").doesNotExist());
    }

    @Test
    void summary_piPlanningWithChildren_aggregatesChildSummaries() throws Exception {
        String piId = createEventId(tokenA1, "PI_PLANNING", teamA1);
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\", \"parentEventId\": \""
                                + piId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE_PATH + "/" + piId + "/summary").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netCapacityDays").value(10.0))
                .andExpect(jsonPath("$.memberCount").value(1));
    }

    @Test
    void summary_crossTenant_returns404() throws Exception {
        String eventId = createEventId(tokenA1, "SPRINT", teamA1);

        mockMvc.perform(get(BASE_PATH + "/" + eventId + "/summary").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
