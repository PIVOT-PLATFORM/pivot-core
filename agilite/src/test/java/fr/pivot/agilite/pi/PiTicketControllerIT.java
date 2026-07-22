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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PiBoardController}/{@link PiTicketController} exercising the full
 * Spring context against a real PostgreSQL database (US50.3.1).
 *
 * <p>Covers the aggregated board read, ticket create (Train row/"Unplanned" defaults, cell
 * validation), update (including a drag-drop-style move), delete, error cases, and cross-tenant
 * isolation.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly —
 * paths start with {@code /agilite/pi/cycles}, not {@code /api/agilite/pi/cycles}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PiTicketControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String BASE_PATH = "/agilite/pi/cycles";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenA;
    private String tokenB;
    private String cycleIdA;
    private String teamIdA;
    private String iterationIdA;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tokenA = fixtureA.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tokenB = fixtureB.rawToken();

        MvcResult cycleResult = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"PI\", \"startDate\": \"2026-01-05\"}"))
                .andReturn();
        JsonNode cycleJson = objectMapper.readTree(cycleResult.getResponse().getContentAsString());
        cycleIdA = cycleJson.get("id").asText();
        iterationIdA = cycleJson.get("iterations").get(0).get("id").asText();

        MvcResult teamResult = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Squad Alpha\"}"))
                .andReturn();
        teamIdA = objectMapper.readTree(teamResult.getResponse().getContentAsString()).get("id").asText();
    }

    // -------------------------------------------------------------------------
    // GET /pi/cycles/{id}/board
    // -------------------------------------------------------------------------

    @Test
    void getBoard_returnsIterationsTeamsAndTickets() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"FEATURE\", \"title\": \"Feature 1\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(BASE_PATH + "/" + cycleIdA + "/board").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleId").value(cycleIdA))
                .andExpect(jsonPath("$.iterations.length()").value(6))
                .andExpect(jsonPath("$.teams.length()").value(1))
                .andExpect(jsonPath("$.tickets.length()").value(1))
                .andExpect(jsonPath("$.dependencies.length()").value(0));
    }

    @Test
    void getBoard_crossTenant_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/" + cycleIdA + "/board").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /pi/cycles/{id}/tickets
    // -------------------------------------------------------------------------

    @Test
    void createTicket_withoutTeamOrIteration_placesOnTrainRowAndUnplanned() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"MILESTONE\", \"title\": \"Kickoff\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("MILESTONE"))
                .andExpect(jsonPath("$.teamId").doesNotExist())
                .andExpect(jsonPath("$.iterationId").doesNotExist())
                .andExpect(jsonPath("$.order").value(0));
    }

    @Test
    void createTicket_withTeamAndIteration_placesInCell() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"Story A\", \"teamId\": \"" + teamIdA
                                + "\", \"iterationId\": \"" + iterationIdA + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamId").value(teamIdA))
                .andExpect(jsonPath("$.iterationId").value(iterationIdA));
    }

    @Test
    void createTicket_blankTitle_returns400WithInvalidTitleCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    @Test
    void createTicket_teamFromAnotherCycle_returns400WithInvalidCellCode() throws Exception {
        MvcResult otherCycle = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Autre PI\", \"startDate\": \"2026-03-01\"}"))
                .andReturn();
        String otherCycleId = objectMapper.readTree(otherCycle.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post(BASE_PATH + "/" + otherCycleId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"X\", \"teamId\": \"" + teamIdA + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CELL"));
    }

    @Test
    void createTicket_crossTenant_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"type\": \"STORY\", \"title\": \"X\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // PATCH /pi/cycles/{id}/tickets/{ticketId} — including drag-drop move
    // -------------------------------------------------------------------------

    @Test
    void updateTicket_dragDropMove_relocatesTicket() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"Movable\"}"))
                .andReturn();
        String ticketId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch(BASE_PATH + "/" + cycleIdA + "/tickets/" + ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"teamId\": \"" + teamIdA + "\", \"iterationId\": \"" + iterationIdA + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(teamIdA))
                .andExpect(jsonPath("$.iterationId").value(iterationIdA));
    }

    @Test
    void updateTicket_backToTrainRowAndUnplanned_setsNulls() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"Movable\", \"teamId\": \"" + teamIdA + "\"}"))
                .andReturn();
        String ticketId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(patch(BASE_PATH + "/" + cycleIdA + "/tickets/" + ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"Movable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").doesNotExist());
    }

    @Test
    void updateTicket_unknownId_returns404() throws Exception {
        mockMvc.perform(patch(BASE_PATH + "/" + cycleIdA + "/tickets/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"title\": \"X\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /pi/cycles/{id}/tickets/{ticketId}
    // -------------------------------------------------------------------------

    @Test
    void deleteTicket_returns204() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"Doomed\"}"))
                .andReturn();
        String ticketId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete(BASE_PATH + "/" + cycleIdA + "/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteTicket_crossTenant_returns404() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"Protected\"}"))
                .andReturn();
        String ticketId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete(BASE_PATH + "/" + cycleIdA + "/tickets/" + ticketId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
