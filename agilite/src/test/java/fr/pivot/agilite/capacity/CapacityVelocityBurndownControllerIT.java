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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.Month;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacityVelocityController}/{@link CapacityBurndownController}
 * exercising the full Spring context against a real PostgreSQL database
 * (US11.4.1/US11.4.2).
 *
 * <p>Covers velocity entry (independent committed/completed fields, SPRINT-only guard), history/
 * average (null-exclusion, defaults), the idempotent burndown upsert, and the derived
 * ideal-curve/{@code stale} chart payload. {@code atRisk}/{@code stale} algorithmic behavior
 * itself is covered exhaustively at the pure-function level in {@link
 * CapacityBurndownCalculatorTest} (deterministic {@link java.time.Clock} injection) — this class
 * only verifies the HTTP wiring, using event dates around the real "now" so the SPRING-managed
 * {@code Clock.systemUTC()} bean naturally exercises an in-progress event.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly —
 * paths start with {@code /agilite/capacity}, not {@code /api/agilite/capacity}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityVelocityBurndownControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String BASE_PATH = "/agilite/capacity";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long teamA1;
    private String tokenA1;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA1 = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        teamA1 = fixtureA1.teamId();
        tokenA1 = fixtureA1.rawToken();

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tokenB = fixtureB.rawToken();
    }

    private String createSprintId(final String token, final LocalDate start, final LocalDate end) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"type\": \"SPRINT\", \"name\": \"Sprint\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"" + start + "\", \"endDate\": \"" + end + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createPiPlanningId(final String token) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"type\": \"PI_PLANNING\", \"name\": \"PI\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-06-30\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    // -------------------------------------------------------------------------
    // PATCH /capacity/events/{id}/velocity
    // -------------------------------------------------------------------------

    @Test
    void updateVelocity_committedThenCompletedIndependently() throws Exception {
        String sprintId = createSprintId(tokenA1, LocalDate.of(2026, Month.JANUARY, 5), LocalDate.of(2026, Month.JANUARY, 16));

        mockMvc.perform(patch(BASE_PATH + "/events/" + sprintId + "/velocity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"committedPoints\": 20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committedPoints").value(20))
                .andExpect(jsonPath("$.completedPoints").doesNotExist());

        mockMvc.perform(patch(BASE_PATH + "/events/" + sprintId + "/velocity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"completedPoints\": 18}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committedPoints").value(20))
                .andExpect(jsonPath("$.completedPoints").value(18));
    }

    @Test
    void updateVelocity_onPiPlanningEvent_returns400WithInvalidEventTypeCode() throws Exception {
        String piId = createPiPlanningId(tokenA1);

        mockMvc.perform(patch(BASE_PATH + "/events/" + piId + "/velocity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"committedPoints\": 20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_TYPE_FOR_VELOCITY"));
    }

    @Test
    void updateVelocity_negativePoints_returns400WithInvalidPointsCode() throws Exception {
        String sprintId = createSprintId(tokenA1, LocalDate.of(2026, Month.JANUARY, 5), LocalDate.of(2026, Month.JANUARY, 16));

        mockMvc.perform(patch(BASE_PATH + "/events/" + sprintId + "/velocity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"committedPoints\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POINTS"));
    }

    // -------------------------------------------------------------------------
    // GET /capacity/teams/{teamId}/velocity-history, .../average
    // -------------------------------------------------------------------------

    @Test
    void history_onlyIncludesSprintsWithCompletedPoints() throws Exception {
        String sprintWithVelocity = createSprintId(tokenA1, LocalDate.of(2026, Month.JANUARY, 5), LocalDate.of(2026, Month.JANUARY, 16));
        mockMvc.perform(patch(BASE_PATH + "/events/" + sprintWithVelocity + "/velocity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"completedPoints\": 15}"))
                .andExpect(status().isOk());
        // A second sprint with no completedPoints must not appear in history.
        createSprintId(tokenA1, LocalDate.of(2026, Month.FEBRUARY, 2), LocalDate.of(2026, Month.FEBRUARY, 13));

        mockMvc.perform(get(BASE_PATH + "/teams/" + teamA1 + "/velocity-history")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].completedPoints").value(15));
    }

    @Test
    void average_noCompletedSprint_returnsNulls() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/teams/" + teamA1 + "/velocity-history/average")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageVelocity").doesNotExist())
                .andExpect(jsonPath("$.suggestedCapacity").doesNotExist());
    }

    @Test
    void average_appliesDefaultFactor() throws Exception {
        String sprintId = createSprintId(tokenA1, LocalDate.of(2026, Month.JANUARY, 5), LocalDate.of(2026, Month.JANUARY, 16));
        mockMvc.perform(patch(BASE_PATH + "/events/" + sprintId + "/velocity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"completedPoints\": 20}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE_PATH + "/teams/" + teamA1 + "/velocity-history/average")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageVelocity").value(20.0))
                .andExpect(jsonPath("$.suggestedCapacity").value(17.0));
    }

    @Test
    void history_crossTenant_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/teams/" + teamA1 + "/velocity-history").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // PUT/GET .../burndown
    // -------------------------------------------------------------------------

    @Test
    void upsertBurndownEntry_thenGet_appearsInActual() throws Exception {
        LocalDate today = LocalDate.now();
        String sprintId = createSprintId(tokenA1, today.minusDays(3), today.plusDays(3));

        mockMvc.perform(put(BASE_PATH + "/events/" + sprintId + "/burndown/" + today)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"pointsRemaining\": 10}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE_PATH + "/events/" + sprintId + "/burndown").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actual.length()").value(1))
                .andExpect(jsonPath("$.actual[0].pointsRemaining").value(10))
                .andExpect(jsonPath("$.stale").value(false));
    }

    @Test
    void upsertBurndownEntry_sameDateTwice_replacesRatherThanDuplicates() throws Exception {
        LocalDate today = LocalDate.now();
        String sprintId = createSprintId(tokenA1, today.minusDays(3), today.plusDays(3));

        mockMvc.perform(put(BASE_PATH + "/events/" + sprintId + "/burndown/" + today)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"pointsRemaining\": 10}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(BASE_PATH + "/events/" + sprintId + "/burndown/" + today)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"pointsRemaining\": 7}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE_PATH + "/events/" + sprintId + "/burndown").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actual.length()").value(1))
                .andExpect(jsonPath("$.actual[0].pointsRemaining").value(7));
    }

    @Test
    void getBurndown_noEntriesEventOngoing_isStale() throws Exception {
        LocalDate today = LocalDate.now();
        String sprintId = createSprintId(tokenA1, today.minusDays(3), today.plusDays(3));

        mockMvc.perform(get(BASE_PATH + "/events/" + sprintId + "/burndown").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stale").value(true));
    }

    @Test
    void getBurndown_noCommittedPoints_idealIsEmpty() throws Exception {
        LocalDate today = LocalDate.now();
        String sprintId = createSprintId(tokenA1, today.minusDays(3), today.plusDays(3));

        mockMvc.perform(get(BASE_PATH + "/events/" + sprintId + "/burndown").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ideal.length()").value(0));
    }

    @Test
    void upsertBurndownEntry_dateOutsideEvent_returns400WithDateOutsideEventCode() throws Exception {
        LocalDate today = LocalDate.now();
        String sprintId = createSprintId(tokenA1, today.minusDays(3), today.plusDays(3));
        LocalDate outside = today.plusDays(30);

        mockMvc.perform(put(BASE_PATH + "/events/" + sprintId + "/burndown/" + outside)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"pointsRemaining\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATE_OUTSIDE_EVENT"));
    }

    @Test
    void upsertBurndownEntry_negativePoints_returns400WithInvalidPointsRemainingCode() throws Exception {
        LocalDate today = LocalDate.now();
        String sprintId = createSprintId(tokenA1, today.minusDays(3), today.plusDays(3));

        mockMvc.perform(put(BASE_PATH + "/events/" + sprintId + "/burndown/" + today)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"pointsRemaining\": -1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POINTS_REMAINING"));
    }

    @Test
    void upsertBurndownEntry_onPiPlanningEvent_returns400WithInvalidEventTypeForBurndownCode() throws Exception {
        String piId = createPiPlanningId(tokenA1);

        mockMvc.perform(put(BASE_PATH + "/events/" + piId + "/burndown/" + LocalDate.now())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"pointsRemaining\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_TYPE_FOR_BURNDOWN"));
    }

    @Test
    void getBurndown_crossTenant_returns404() throws Exception {
        LocalDate today = LocalDate.now();
        String sprintId = createSprintId(tokenA1, today.minusDays(3), today.plusDays(3));

        mockMvc.perform(get(BASE_PATH + "/events/" + sprintId + "/burndown").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
