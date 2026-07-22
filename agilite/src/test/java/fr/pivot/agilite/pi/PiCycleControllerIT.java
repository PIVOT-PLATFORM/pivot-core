package fr.pivot.agilite.pi;

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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PiCycleController} exercising the full Spring context against a
 * real PostgreSQL database (US50.1.1).
 *
 * <p>Covers cycle create (defaults/explicit iteration params, bounds), get/list/update/delete,
 * manual/import Train team management (dedup, cross-tenant silent skip, no-importable-team),
 * iteration adjustment, and cross-tenant/access-denial 404 isolation.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix — paths start with {@code
 * /agilite/pi/cycles}, not {@code /api/agilite/pi/cycles}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PiCycleControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String BASE_PATH = "/agilite/pi/cycles";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long userA1;
    private long teamA1;
    private long teamA2;
    private long teamA3;
    private String tokenA1;

    private long userA2;
    private String tokenA2;

    private long tenantB;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA1 = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA1.tenantId();
        userA1 = fixtureA1.userId();
        teamA1 = fixtureA1.teamId();
        tokenA1 = fixtureA1.rawToken();

        teamA2 = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, "team-a2-" + tenantA);
        teamA3 = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, "team-a3-" + tenantA);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA2, userA1);

        userA2 = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true);
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA2, userA2);
        tokenA2 = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), userA2, "active",
                Instant.now().plusSeconds(3600));

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantB = fixtureB.tenantId();
        tokenB = fixtureB.rawToken();
    }

    private String createCycleBody(final String startDate) {
        return "{\"name\": \"PI 2026.Q3\", \"artName\": \"ART Nova\", \"startDate\": \"" + startDate + "\"}";
    }

    private String createCycleId(final String token) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(createCycleBody("2026-01-05")))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    // -------------------------------------------------------------------------
    // POST /pi/cycles
    // -------------------------------------------------------------------------

    @Test
    void create_withDefaults_returns201WithFiveIterationsPlusIpSprint() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(createCycleBody("2026-01-05")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("PI 2026.Q3"))
                .andExpect(jsonPath("$.artName").value("ART Nova"))
                .andExpect(jsonPath("$.status").value("PREPARATION"))
                .andExpect(jsonPath("$.iterations.length()").value(6))
                .andExpect(jsonPath("$.iterations[0].label").value("IT1"))
                .andExpect(jsonPath("$.iterations[5].label").value("IP Sprint"))
                .andExpect(jsonPath("$.teams.length()").value(0));
    }

    @Test
    void create_withExplicitIterationParams_generatesRequestedCount() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"PI Court\", \"startDate\": \"2026-02-01\", "
                                + "\"iterationCount\": 2, \"iterationWeeks\": 1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iterations.length()").value(3))
                .andExpect(jsonPath("$.iterations[2].label").value("IP Sprint"));
    }

    @Test
    void create_withBlankName_returns400WithInvalidNameCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"\", \"startDate\": \"2026-01-05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));
    }

    @Test
    void create_withIterationCountOutOfBounds_returns400WithInvalidIterationParamsCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"PI\", \"startDate\": \"2026-01-05\", \"iterationCount\": 13}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ITERATION_PARAMS"));
    }

    @Test
    void create_withIterationWeeksOutOfBounds_returns400WithInvalidIterationParamsCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"PI\", \"startDate\": \"2026-01-05\", \"iterationWeeks\": 7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ITERATION_PARAMS"));
    }

    // -------------------------------------------------------------------------
    // GET /pi/cycles/{id}, GET /pi/cycles
    // -------------------------------------------------------------------------

    @Test
    void findById_asCreator_returns200() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(get(BASE_PATH + "/" + cycleId).header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cycleId));
    }

    @Test
    void findById_crossTenant_returns404() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(get(BASE_PATH + "/" + cycleId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void findById_unrelatedSameTenantUser_returns404() throws Exception {
        String cycleId = createCycleId(tokenA1);

        // userA2 is not the creator and, before any import, is not a member of any imported team.
        mockMvc.perform(get(BASE_PATH + "/" + cycleId).header("Authorization", "Bearer " + tokenA2))
                .andExpect(status().isNotFound());
    }

    @Test
    void findById_memberOfImportedTrainTeam_gainsAccess() throws Exception {
        String cycleId = createCycleId(tokenA1);
        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamIds\": [" + teamA2 + "]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE_PATH + "/" + cycleId).header("Authorization", "Bearer " + tokenA2))
                .andExpect(status().isOk());
    }

    @Test
    void findById_unknownId_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_returnsOnlyAccessibleCycles() throws Exception {
        createCycleId(tokenA1);
        createCycleId(tokenB);

        mockMvc.perform(get(BASE_PATH).header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // -------------------------------------------------------------------------
    // PATCH /pi/cycles/{id}
    // -------------------------------------------------------------------------

    @Test
    void update_status_transitionsFreely() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(patch(BASE_PATH + "/" + cycleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"status\": \"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        mockMvc.perform(patch(BASE_PATH + "/" + cycleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"status\": \"PREPARATION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARATION"));
    }

    @Test
    void update_crossTenant_returns404() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(patch(BASE_PATH + "/" + cycleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"name\": \"hijack\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /pi/cycles/{id}
    // -------------------------------------------------------------------------

    @Test
    void delete_asCreator_returns204AndCascades() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(delete(BASE_PATH + "/" + cycleId).header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(BASE_PATH + "/" + cycleId).header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_crossTenant_returns404() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(delete(BASE_PATH + "/" + cycleId).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Train teams
    // -------------------------------------------------------------------------

    @Test
    void addManualTeam_returns201WithServerAssignedColorWhenOmitted() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"Partenaire externe\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Partenaire externe"))
                .andExpect(jsonPath("$.sourceTeamId").doesNotExist())
                .andExpect(jsonPath("$.color").isNotEmpty())
                .andExpect(jsonPath("$.order").value(0));
    }

    @Test
    void addManualTeam_withExplicitColor_keepsIt() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"Partenaire\", \"color\": \"#123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.color").value("#123456"));
    }

    @Test
    void importTeams_newTeam_returns201WithSnapshot() throws Exception {
        String cycleId = createCycleId(tokenA1);

        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamIds\": [" + teamA2 + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.teams[0].sourceTeamId").value(teamA2));
    }

    @Test
    void importTeams_mixedDuplicateAndNew_importsOnlyNew() throws Exception {
        String cycleId = createCycleId(tokenA1);
        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamIds\": [" + teamA2 + "]}"))
                .andExpect(status().isCreated());

        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA3, userA1);
        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamIds\": [" + teamA2 + ", " + teamA3 + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.teams[0].sourceTeamId").value(teamA3));
    }

    @Test
    void importTeams_onlyDuplicate_returns400WithNoImportableTeamCode() throws Exception {
        String cycleId = createCycleId(tokenA1);
        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamIds\": [" + teamA2 + "]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamIds\": [" + teamA2 + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_IMPORTABLE_TEAM"));
    }

    @Test
    void importTeams_crossTenantTeamId_returns400WithNoImportableTeamCode() throws Exception {
        String cycleId = createCycleId(tokenA1);
        long foreignTeam = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantB, "foreign-team");

        mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamIds\": [" + foreignTeam + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_IMPORTABLE_TEAM"));
    }

    @Test
    void updateTeam_changesNameColorOrder() throws Exception {
        String cycleId = createCycleId(tokenA1);
        MvcResult created = mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"Original\"}"))
                .andReturn();
        String teamId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch(BASE_PATH + "/" + cycleId + "/teams/" + teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"Renamed\", \"color\": \"#ABCDEF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.color").value("#ABCDEF"));
    }

    @Test
    void deleteTeam_fallsTicketsBackToTrainRow() throws Exception {
        String cycleId = createCycleId(tokenA1);
        MvcResult createdTeam = mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"name\": \"Squad\"}"))
                .andReturn();
        String teamId = objectMapper.readTree(createdTeam.getResponse().getContentAsString()).get("id").asText();

        MvcResult createdTicket = mockMvc.perform(post(BASE_PATH + "/" + cycleId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"type\": \"STORY\", \"title\": \"Story 1\", \"teamId\": \"" + teamId + "\"}"))
                .andReturn();
        String ticketId = objectMapper.readTree(createdTicket.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete(BASE_PATH + "/" + cycleId + "/teams/" + teamId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + cycleId + "/board").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tickets.length()").value(1))
                .andExpect(jsonPath("$.tickets[0].id").value(ticketId))
                .andExpect(jsonPath("$.tickets[0].teamId").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // Iterations
    // -------------------------------------------------------------------------

    @Test
    void updateIteration_validRange_returns200() throws Exception {
        String cycleId = createCycleId(tokenA1);
        MvcResult cycleResult = mockMvc.perform(get(BASE_PATH + "/" + cycleId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        String iterationId = objectMapper.readTree(cycleResult.getResponse().getContentAsString())
                .get("iterations").get(0).get("id").asText();

        mockMvc.perform(patch(BASE_PATH + "/" + cycleId + "/iterations/" + iterationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"label\": \"IT1 décalé\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iterations[0].label").value("IT1 décalé"));
    }

    @Test
    void updateIteration_startAfterEnd_returns400WithInvalidDateRangeCode() throws Exception {
        String cycleId = createCycleId(tokenA1);
        MvcResult cycleResult = mockMvc.perform(get(BASE_PATH + "/" + cycleId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        String iterationId = objectMapper.readTree(cycleResult.getResponse().getContentAsString())
                .get("iterations").get(0).get("id").asText();

        mockMvc.perform(patch(BASE_PATH + "/" + cycleId + "/iterations/" + iterationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"startDate\": \"2026-06-01\", \"endDate\": \"2026-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

}
