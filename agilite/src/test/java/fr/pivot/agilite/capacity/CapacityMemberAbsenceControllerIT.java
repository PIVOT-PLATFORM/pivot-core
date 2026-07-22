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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CapacityMemberController} exercising the full Spring context
 * against a real PostgreSQL database (US11.2.1/US11.2.2).
 *
 * <p>Covers roster listing/adjustment (availability bounds, exclusion), absence create/delete
 * (event-period overlap validation), the RGPD no-reason-field guarantee, and cross-tenant
 * isolation.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly —
 * paths start with {@code /agilite/capacity/events}, not {@code /api/agilite/capacity/events}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityMemberAbsenceControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String BASE_PATH = "/agilite/capacity/events";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long teamA1;
    private String tokenA1;
    private String eventId;
    private String memberId;

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

        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamA1
                                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        eventId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        MvcResult membersResult = mockMvc.perform(get(BASE_PATH + "/" + eventId + "/members")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode members = objectMapper.readTree(membersResult.getResponse().getContentAsString());
        memberId = members.get(0).get("id").asText();
    }

    // -------------------------------------------------------------------------
    // PATCH capacity/events/EVENT_ID/members/MEMBER_ID
    // -------------------------------------------------------------------------

    @Test
    void updateMember_excludesMember_reflectedInResponse() throws Exception {
        mockMvc.perform(patch(BASE_PATH + "/" + eventId + "/members/" + memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"excluded\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excluded").value(true));
    }

    @Test
    void updateMember_availabilityOutOfBounds_returns400WithInvalidAvailabilityCode() throws Exception {
        mockMvc.perform(patch(BASE_PATH + "/" + eventId + "/members/" + memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"availabilityPercent\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AVAILABILITY"));
    }

    @Test
    void updateMember_excludedMemberStaysVisibleInList() throws Exception {
        mockMvc.perform(patch(BASE_PATH + "/" + eventId + "/members/" + memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"excluded\": true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(BASE_PATH + "/" + eventId + "/members").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].excluded").value(true));
    }

    @Test
    void updateMember_crossTenant_returns404() throws Exception {
        mockMvc.perform(patch(BASE_PATH + "/" + eventId + "/members/" + memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"excluded\": true}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST/DELETE .../members/{memberId}/absences
    // -------------------------------------------------------------------------

    @Test
    void createAbsence_withinEventPeriod_returns201() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + eventId + "/members/" + memberId + "/absences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"dateDebut\": \"2026-01-07\", \"dateFin\": \"2026-01-08\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dateDebut").value("2026-01-07"))
                .andExpect(jsonPath("$.dateFin").value("2026-01-08"));
    }

    @Test
    void createAbsence_partiallyOverlapping_isAccepted() throws Exception {
        // Absence starts 3 days before the event and ends inside it — partial overlap allowed.
        mockMvc.perform(post(BASE_PATH + "/" + eventId + "/members/" + memberId + "/absences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"dateDebut\": \"2026-01-02\", \"dateFin\": \"2026-01-06\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createAbsence_fullyOutsideEvent_returns400WithAbsenceOutsideEventCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + eventId + "/members/" + memberId + "/absences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"dateDebut\": \"2026-03-01\", \"dateFin\": \"2026-03-05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ABSENCE_OUTSIDE_EVENT"));
    }

    @Test
    void createAbsence_startAfterEnd_returns400WithInvalidDateRangeCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + eventId + "/members/" + memberId + "/absences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"dateDebut\": \"2026-01-08\", \"dateFin\": \"2026-01-07\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
    }

    /**
     * RGPD (US11.2.2 §Architecture) — a {@code motif} field sent in the request body must never
     * be persisted or returned, even though the request otherwise succeeds. This module's own
     * {@code application-test.yml} (unlike the aggregated shell app's {@code application.yml},
     * which explicitly re-enables {@code
     * spring.jackson.deserialization.fail-on-unknown-properties} to reject spoofed fields on
     * account/admin endpoints — see {@code src/main/resources/application.yml} at the repo root)
     * does not set that override, so Jackson 3.x's own new lenient default applies here: an
     * unknown JSON property is silently ignored, not rejected — confirmed empirically. The
     * request therefore succeeds (201) exactly as the AC describes, and the assertion that
     * matters is that {@code motif} never reaches the persisted absence or its response.
     */
    @Test
    void createAbsence_withMotifField_createdButNeverPersistedOrReturned() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH + "/" + eventId + "/members/" + memberId + "/absences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"dateDebut\": \"2026-01-07\", \"dateFin\": \"2026-01-08\", \"motif\": \"congé maladie\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("motif").doesNotContain("congé maladie");

        MvcResult listResult = mockMvc.perform(get(BASE_PATH + "/" + eventId + "/members")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode absences = objectMapper.readTree(listResult.getResponse().getContentAsString()).get(0).get("absences");
        assertThat(absences).hasSize(1);
        assertThat(listResult.getResponse().getContentAsString()).doesNotContain("motif").doesNotContain("congé maladie");
    }

    @Test
    void deleteAbsence_returns204() throws Exception {
        MvcResult createResult = mockMvc.perform(post(BASE_PATH + "/" + eventId + "/members/" + memberId + "/absences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"dateDebut\": \"2026-01-07\", \"dateFin\": \"2026-01-08\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String absenceId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete(BASE_PATH + "/" + eventId + "/absences/" + absenceId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNoContent());
    }

    @Test
    void createAbsence_crossTenant_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + eventId + "/members/" + memberId + "/absences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"dateDebut\": \"2026-01-07\", \"dateFin\": \"2026-01-08\"}"))
                .andExpect(status().isNotFound());
    }
}
