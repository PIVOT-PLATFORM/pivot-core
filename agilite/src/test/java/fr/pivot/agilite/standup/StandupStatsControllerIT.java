package fr.pivot.agilite.standup;

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
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link StandupStatsController} exercising the full Spring context against
 * a real PostgreSQL database and Redis provided by Testcontainers (US10.3.1).
 *
 * <p>Covers the backlog AC: a completed session and its per-participant aggregated speaking time
 * appearing in the stats, the default 30-day period, the {@code INVALID_DATE_RANGE} error, and
 * tenant/team-membership isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StandupStatsControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String SESSIONS_PATH = "/agilite/standup/sessions";
    private static final String STATS_PATH = "/agilite/standup/stats";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long teamA;
    private long teamMemberA1;
    private String tokenA1;

    private long teamB;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA1 = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        teamA = fixtureA1.teamId();
        teamMemberA1 = fixtureA1.teamMemberId();
        tokenA1 = fixtureA1.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        teamB = fixtureB.teamId();
        tokenB = fixtureB.rawToken();
    }

    @Test
    void getStats_afterCompletedSession_includesSessionAndParticipantAggregates() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily terminé", teamMemberA1);
        mockMvc.perform(post(SESSIONS_PATH + "/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk());
        // Single participant → immediately ends the session on next().
        mockMvc.perform(post(SESSIONS_PATH + "/" + sessionId + "/next")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk());

        mockMvc.perform(get(STATS_PATH).param("teamId", String.valueOf(teamA))
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].name").value("Daily terminé"))
                .andExpect(jsonPath("$.sessions[0].durationSeconds").isNumber())
                .andExpect(jsonPath("$.participants.length()").value(1))
                .andExpect(jsonPath("$.participants[0].sessionCount").value(1));
    }

    @Test
    void getStats_withNoDateRange_defaultsToLast30Days() throws Exception {
        mockMvc.perform(get(STATS_PATH).param("teamId", String.valueOf(teamA))
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(0))
                .andExpect(jsonPath("$.participants.length()").value(0));
    }

    @Test
    void getStats_withFromAfterTo_returns400WithInvalidDateRangeCode() throws Exception {
        mockMvc.perform(get(STATS_PATH).param("teamId", String.valueOf(teamA))
                        .param("from", "2026-08-01").param("to", "2026-07-01")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void getStats_nonMemberOfTeam_returns404() throws Exception {
        mockMvc.perform(get(STATS_PATH).param("teamId", String.valueOf(teamA))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void getStats_teamOfAnotherTenant_returns404() throws Exception {
        mockMvc.perform(get(STATS_PATH).param("teamId", String.valueOf(teamB))
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNotFound());
    }

    private String createSessionFor(
            final String token, final long teamId, final String name, final long teamMemberId) throws Exception {
        MvcResult result = mockMvc.perform(post(SESSIONS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"teamId\": " + teamId + ", \"name\": \"" + name + "\", "
                                + "\"participantTeamMemberIds\": [" + teamMemberId + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
