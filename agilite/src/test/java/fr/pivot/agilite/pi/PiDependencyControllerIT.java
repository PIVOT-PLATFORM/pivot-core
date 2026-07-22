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
 * Integration tests for {@link PiDependencyController} exercising the full Spring context
 * against a real PostgreSQL database (US50.3.2).
 *
 * <p>Covers dependency create (default status, self-dependency, cross-cycle ticket, duplicate,
 * the mandatory anti-cycle rejection on a 3-node A→B→C→A chain), update, delete, and
 * cross-tenant isolation.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly —
 * paths start with {@code /agilite/pi/cycles}, not {@code /api/agilite/pi/cycles}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PiDependencyControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String BASE_PATH = "/agilite/pi/cycles";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenA;
    private String tokenB;
    private String cycleIdA;
    private String ticketA;
    private String ticketB;
    private String ticketC;

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
        cycleIdA = objectMapper.readTree(cycleResult.getResponse().getContentAsString()).get("id").asText();

        ticketA = createTicket("Ticket A");
        ticketB = createTicket("Ticket B");
        ticketC = createTicket("Ticket C");
    }

    private String createTicket(final String title) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"" + title + "\"}"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void createDependency(final String from, final String to) throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + from + "\", \"toTicketId\": \"" + to + "\"}"))
                .andExpect(status().isCreated());
    }

    // -------------------------------------------------------------------------
    // POST /pi/cycles/{id}/dependencies
    // -------------------------------------------------------------------------

    @Test
    void create_defaultsToOkStatus() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + ticketB + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.fromTicketId").value(ticketA))
                .andExpect(jsonPath("$.toTicketId").value(ticketB));
    }

    @Test
    void create_explicitBlockedStatus() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + ticketB
                                + "\", \"status\": \"BLOCKED\", \"note\": \"waiting on infra\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.note").value("waiting on infra"));
    }

    @Test
    void create_selfDependency_returns400WithSelfDependencyCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + ticketA + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_DEPENDENCY"));
    }

    @Test
    void create_ticketFromAnotherCycle_returns400WithInvalidTicketCode() throws Exception {
        MvcResult otherCycle = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"name\": \"Autre PI\", \"startDate\": \"2026-03-01\"}"))
                .andReturn();
        String otherCycleId = objectMapper.readTree(otherCycle.getResponse().getContentAsString()).get("id").asText();
        MvcResult foreignTicket = mockMvc.perform(post(BASE_PATH + "/" + otherCycleId + "/tickets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"type\": \"STORY\", \"title\": \"Foreign\"}"))
                .andReturn();
        String foreignTicketId = objectMapper.readTree(foreignTicket.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + foreignTicketId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TICKET"));
    }

    @Test
    void create_duplicatePair_returns400WithDuplicateDependencyCode() throws Exception {
        createDependency(ticketA, ticketB);

        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + ticketB + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEPENDENCY"));
    }

    @Test
    void create_threeNodeCycle_rejectedOnThirdLink() throws Exception {
        // A -> B -> C already linked; C -> A would close the cycle A->B->C->A.
        createDependency(ticketA, ticketB);
        createDependency(ticketB, ticketC);

        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketC + "\", \"toTicketId\": \"" + ticketA + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_CYCLE"));
    }

    @Test
    void create_crossTenant_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + ticketB + "\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // PATCH / DELETE /pi/cycles/{id}/dependencies/{dependencyId}
    // -------------------------------------------------------------------------

    @Test
    void update_changesStatusAndNote() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + ticketB + "\"}"))
                .andReturn();
        JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        String dependencyId = createdJson.get("id").asText();

        mockMvc.perform(patch(BASE_PATH + "/" + cycleIdA + "/dependencies/" + dependencyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"status\": \"BLOCKED\", \"note\": \"escalated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.note").value("escalated"))
                .andExpect(jsonPath("$.fromTicketId").value(ticketA))
                .andExpect(jsonPath("$.toTicketId").value(ticketB));
    }

    @Test
    void update_unknownId_returns404() throws Exception {
        mockMvc.perform(patch(BASE_PATH + "/" + cycleIdA + "/dependencies/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"status\": \"BLOCKED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + ticketB + "\"}"))
                .andReturn();
        String dependencyId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete(BASE_PATH + "/" + cycleIdA + "/dependencies/" + dependencyId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_crossTenant_returns404() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE_PATH + "/" + cycleIdA + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA)
                        .content("{\"fromTicketId\": \"" + ticketA + "\", \"toTicketId\": \"" + ticketB + "\"}"))
                .andReturn();
        String dependencyId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete(BASE_PATH + "/" + cycleIdA + "/dependencies/" + dependencyId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTicket_cascadesDependencies() throws Exception {
        createDependency(ticketA, ticketB);

        mockMvc.perform(delete(BASE_PATH + "/" + cycleIdA + "/tickets/" + ticketA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + cycleIdA + "/board")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies.length()").value(0));
    }
}
