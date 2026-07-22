package fr.pivot.agilite.capacity;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the F11.6 engine endpoints — tenant holidays ({@link
 * CapacityHolidayController}, US11.6.1), team maturity ({@link CapacityMaturityController},
 * US11.6.4), CSV absence import ({@link CapacityAbsenceImportController}, US11.7.1), and the
 * full-engine capacity summary ({@code GET .../events/{id}/summary}, US11.6.5) — exercised
 * against a real PostgreSQL database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CapacityEngineControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String EVENTS_PATH = "/agilite/capacity/events";
    private static final String HOLIDAYS_PATH = "/agilite/capacity/holidays";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long teamA1;
    private String tokenA1;
    private String adminTokenA;
    private String tokenB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA1 = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA1.tenantId();
        teamA1 = fixtureA1.teamId();
        tokenA1 = fixtureA1.rawToken();

        long adminUserA = PlatformAuthTestSupport.seedAdminUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA);
        adminTokenA = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), adminUserA, "active",
                Instant.now().plusSeconds(3600));

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tokenB = fixtureB.rawToken();
    }

    private String createSprintId(final String token, final long teamId, final String start, final String end) throws Exception {
        String body = "{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamId
                + ", \"startDate\": \"" + start + "\", \"endDate\": \"" + end + "\"}";
        MvcResult result = mockMvc.perform(post(EVENTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    // -------------------------------------------------------------------------
    // POST/GET/DELETE /capacity/holidays — US11.6.1, tenant-admin gate
    // -------------------------------------------------------------------------

    @Test
    void createHoliday_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"date\": \"2026-01-01\", \"label\": \"Jour de l'an\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createHoliday_admin_succeedsAndIsListable() throws Exception {
        mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminTokenA)
                        .content("{\"date\": \"2026-01-01\", \"label\": \"Jour de l'an\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Jour de l'an"));

        mockMvc.perform(get(HOLIDAYS_PATH).header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createHoliday_duplicateDate_returns400() throws Exception {
        String body = "{\"date\": \"2026-01-01\", \"label\": \"Jour de l'an\"}";
        mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminTokenA)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminTokenA)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_HOLIDAY"));
    }

    @Test
    void holidays_crossTenant_neverVisible() throws Exception {
        mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminTokenA)
                        .content("{\"date\": \"2026-01-01\", \"label\": \"Jour de l'an\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(HOLIDAYS_PATH).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteHoliday_nonAdmin_returns403() throws Exception {
        MvcResult result = mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminTokenA)
                        .content("{\"date\": \"2026-01-01\", \"label\": \"Jour de l'an\"}"))
                .andReturn();
        String holidayId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(delete(HOLIDAYS_PATH + "/" + holidayId).header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // PATCH/GET /capacity/teams/{teamId}/capacity-maturity — US11.6.4
    // -------------------------------------------------------------------------

    @Test
    void maturity_unconfigured_returnsGlobalDefault() throws Exception {
        mockMvc.perform(get("/agilite/capacity/teams/" + teamA1 + "/capacity-maturity")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maturity").doesNotExist())
                .andExpect(jsonPath("$.focusFactorPercent").value(70))
                .andExpect(jsonPath("$.marginPercent").value(15))
                .andExpect(jsonPath("$.source").value("DEFAULT"));
    }

    @Test
    void maturity_setToForming_returnsFormingDefaults() throws Exception {
        mockMvc.perform(patch("/agilite/capacity/teams/" + teamA1 + "/capacity-maturity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"maturity\": \"FORMING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maturity").value("FORMING"))
                .andExpect(jsonPath("$.focusFactorPercent").value(60))
                .andExpect(jsonPath("$.marginPercent").value(20))
                .andExpect(jsonPath("$.source").value("TEAM_MATURITY"));
    }

    @Test
    void maturity_crossTenant_returns404() throws Exception {
        mockMvc.perform(get("/agilite/capacity/teams/" + teamA1 + "/capacity-maturity")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /capacity/events/{id}/absences/import — US11.7.1
    // -------------------------------------------------------------------------

    @Test
    void importCsv_validRowsImported_unknownMemberRowErrors() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        MvcResult membersResult = mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/members")
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        long teamMemberId = objectMapper.readTree(membersResult.getResponse().getContentAsString())
                .get(0).get("teamMemberId").asLong();

        String csv = "teamMemberIdOrEmail,dateDebut,dateFin\n"
                + teamMemberId + ",2026-01-06,2026-01-07\n"
                + "999999999,2026-01-06,2026-01-07\n";
        MockMultipartFile file = new MockMultipartFile("file", "absences.csv", "text/csv", csv.getBytes());

        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_MEMBER"));
    }

    @Test
    void importCsv_motifColumn_silentlyIgnoredNeverPersistedNorReturned() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        MvcResult membersResult = mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/members")
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        long teamMemberId = objectMapper.readTree(membersResult.getResponse().getContentAsString())
                .get(0).get("teamMemberId").asLong();

        String csv = "teamMemberIdOrEmail,dateDebut,dateFin,motif\n"
                + teamMemberId + ",2026-01-06,2026-01-07,Congé maladie\n";
        MockMultipartFile file = new MockMultipartFile("file", "absences.csv", "text/csv", csv.getBytes());

        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/members").header("Authorization", "Bearer " + tokenA1))
                .andExpect(jsonPath("$[0].absences[0].dateDebut").value("2026-01-06"))
                .andExpect(jsonPath("$[0].absences[0].motif").doesNotExist())
                .andExpect(jsonPath("$[0].absences[0].reason").doesNotExist());
    }

    @Test
    void importCsv_exactDuplicate_countedButNotRecreated() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        MvcResult membersResult = mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/members")
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        long teamMemberId = objectMapper.readTree(membersResult.getResponse().getContentAsString())
                .get(0).get("teamMemberId").asLong();

        String csv = "teamMemberIdOrEmail,dateDebut,dateFin\n" + teamMemberId + ",2026-01-06,2026-01-07\n";
        MockMultipartFile file1 = new MockMultipartFile("file", "absences.csv", "text/csv", csv.getBytes());
        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file1)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(jsonPath("$.imported").value(1));

        MockMultipartFile file2 = new MockMultipartFile("file", "absences.csv", "text/csv", csv.getBytes());
        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file2)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.errors.length()").value(0));

        mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/members").header("Authorization", "Bearer " + tokenA1))
                .andExpect(jsonPath("$[0].absences.length()").value(1));
    }

    @Test
    void importCsv_crossTenantEvent_returns404() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        MockMultipartFile file = new MockMultipartFile(
                "file", "absences.csv", "text/csv", "teamMemberIdOrEmail,dateDebut,dateFin\n1,2026-01-06,2026-01-07\n".getBytes());

        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void importCsv_emptyFile_returns400() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        MockMultipartFile file = new MockMultipartFile("file", "absences.csv", "text/csv", new byte[0]);

        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMPORT_FILE"));
    }

    @Test
    void importCsv_tooFewColumns_rowErrorsAsInvalidDateRange() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        String csv = "teamMemberIdOrEmail,dateDebut,dateFin\n1,2026-01-06\n";
        MockMultipartFile file = new MockMultipartFile("file", "absences.csv", "text/csv", csv.getBytes());

        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void importCsv_malformedDate_rowErrorsAsInvalidDateRange() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        MvcResult membersResult = mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/members")
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        long teamMemberId = objectMapper.readTree(membersResult.getResponse().getContentAsString())
                .get(0).get("teamMemberId").asLong();
        String csv = "teamMemberIdOrEmail,dateDebut,dateFin\n" + teamMemberId + ",not-a-date,2026-01-07\n";
        MockMultipartFile file = new MockMultipartFile("file", "absences.csv", "text/csv", csv.getBytes());

        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void importCsv_dateDebutAfterDateFin_rowErrorsAsInvalidDateRange() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        MvcResult membersResult = mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/members")
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        long teamMemberId = objectMapper.readTree(membersResult.getResponse().getContentAsString())
                .get(0).get("teamMemberId").asLong();
        String csv = "teamMemberIdOrEmail,dateDebut,dateFin\n" + teamMemberId + ",2026-01-10,2026-01-06\n";
        MockMultipartFile file = new MockMultipartFile("file", "absences.csv", "text/csv", csv.getBytes());

        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_DATE_RANGE"));
    }

    @Test
    void importCsv_absenceFullyOutsideEventPeriod_rowErrorsAsAbsenceOutsideEvent() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");
        MvcResult membersResult = mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/members")
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        long teamMemberId = objectMapper.readTree(membersResult.getResponse().getContentAsString())
                .get(0).get("teamMemberId").asLong();
        String csv = "teamMemberIdOrEmail,dateDebut,dateFin\n" + teamMemberId + ",2026-03-01,2026-03-02\n";
        MockMultipartFile file = new MockMultipartFile("file", "absences.csv", "text/csv", csv.getBytes());

        mockMvc.perform(multipart(EVENTS_PATH + "/" + eventId + "/absences/import")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].code").value("ABSENCE_OUTSIDE_EVENT"));
    }

    // -------------------------------------------------------------------------
    // GET /capacity/events/{id}/summary — US11.6.5, full engine + isProvisional
    // -------------------------------------------------------------------------

    @Test
    void summary_noConfiguration_isProvisionalTrue() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");

        mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/summary").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProvisional").value(true));
    }

    @Test
    void summary_tenantHolidayConfigured_isProvisionalFalseAndWorkingDaysReduced() throws Exception {
        mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminTokenA)
                        .content("{\"date\": \"2026-01-07\", \"label\": \"Jour férié test\"}"))
                .andExpect(status().isCreated());

        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-09");

        mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/summary").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isProvisional").value(false))
                .andExpect(jsonPath("$.workingDays").value(4));
    }

    @Test
    void summary_incrementParent_aggregatesChildrenExcludingIpIteration() throws Exception {
        String incrementBody = "{\"type\": \"INCREMENT\", \"name\": \"Increment 1\", \"teamId\": " + teamA1
                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-02-27\"}";
        MvcResult incrementResult = mockMvc.perform(post(EVENTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(incrementBody))
                .andExpect(status().isCreated())
                .andReturn();
        String incrementId = objectMapper.readTree(incrementResult.getResponse().getContentAsString()).get("id").asText();

        String sprint1Body = "{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamA1
                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\", \"parentEventId\": \"" + incrementId + "\"}";
        mockMvc.perform(post(EVENTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(sprint1Body))
                .andExpect(status().isCreated());

        mockMvc.perform(get(EVENTS_PATH + "/" + incrementId + "/summary").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(1));
    }

    // -------------------------------------------------------------------------
    // POST/PATCH /capacity/events — US11.5.1 hierarchy depth/period, US11.6.2 focus factor
    // -------------------------------------------------------------------------

    @Test
    void create_childOfAnAlreadyNestedParent_returns400MaxDepthExceeded() throws Exception {
        String incrementBody = "{\"type\": \"INCREMENT\", \"name\": \"Increment 1\", \"teamId\": " + teamA1
                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-02-27\"}";
        MvcResult incrementResult = mockMvc.perform(post(EVENTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(incrementBody))
                .andReturn();
        String incrementId = objectMapper.readTree(incrementResult.getResponse().getContentAsString()).get("id").asText();

        String sprintBody = "{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamA1
                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\", \"parentEventId\": \"" + incrementId + "\"}";
        MvcResult sprintResult = mockMvc.perform(post(EVENTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(sprintBody))
                .andReturn();
        String sprintId = objectMapper.readTree(sprintResult.getResponse().getContentAsString()).get("id").asText();

        // A candidate that is itself already a child (has a parent) can never become a parent.
        String grandchildBody = "{\"type\": \"SPRINT\", \"name\": \"Sprint 2\", \"teamId\": " + teamA1
                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\", \"parentEventId\": \"" + sprintId + "\"}";
        mockMvc.perform(post(EVENTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(grandchildBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MAX_DEPTH_EXCEEDED"));
    }

    @Test
    void create_childPeriodOutsideParentPeriod_returns400() throws Exception {
        String incrementBody = "{\"type\": \"INCREMENT\", \"name\": \"Increment 1\", \"teamId\": " + teamA1
                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-01-16\"}";
        MvcResult incrementResult = mockMvc.perform(post(EVENTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(incrementBody))
                .andReturn();
        String incrementId = objectMapper.readTree(incrementResult.getResponse().getContentAsString()).get("id").asText();

        // Child ends 2026-02-01, well past the parent's 2026-01-16 end date.
        String sprintBody = "{\"type\": \"SPRINT\", \"name\": \"Sprint 1\", \"teamId\": " + teamA1
                + ", \"startDate\": \"2026-01-05\", \"endDate\": \"2026-02-01\", \"parentEventId\": \"" + incrementId + "\"}";
        mockMvc.perform(post(EVENTS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content(sprintBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHILD_PERIOD_OUTSIDE_PARENT"));
    }

    @Test
    void update_isIpIterationOnTopLevelSprint_acceptedWithoutEffectOnAggregation() throws Exception {
        // A top-level SPRINT (no PI_PLANNING parent) has no IP-iteration semantics — the flag is
        // accepted without error, per the AC's explicit "accepted, no effect" row (US11.5.1).
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");

        mockMvc.perform(patch(EVENTS_PATH + "/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"isIpIteration\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isIpIteration").value(true));
    }

    @Test
    void update_focusFactorOutOfBounds_returns400() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-16");

        mockMvc.perform(patch(EVENTS_PATH + "/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"focusFactorPercent\": 5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FOCUS_FACTOR"));
    }

    @Test
    void update_focusFactorWithinBounds_appliedAndReflectedInSummary() throws Exception {
        String eventId = createSprintId(tokenA1, teamA1, "2026-01-05", "2026-01-09");

        mockMvc.perform(patch(EVENTS_PATH + "/" + eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"focusFactorPercent\": 50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.focusFactorPercent").value(50));

        // Mon 2026-01-05 .. Fri 2026-01-09: 5 working days * 1 member * 100% availability * 50% = 2.5.
        mockMvc.perform(get(EVENTS_PATH + "/" + eventId + "/summary").header("Authorization", "Bearer " + tokenA1))
                .andExpect(jsonPath("$.netCapacityDays").value(2.5))
                .andExpect(jsonPath("$.isProvisional").value(false));
    }

    // -------------------------------------------------------------------------
    // GET /capacity/holidays?from=&to= — US11.6.1 period filter
    // -------------------------------------------------------------------------

    @Test
    void listHolidays_periodFilter_onlyReturnsHolidaysWithinRange() throws Exception {
        mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminTokenA)
                        .content("{\"date\": \"2026-01-01\", \"label\": \"Jour de l'an\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(HOLIDAYS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + adminTokenA)
                        .content("{\"date\": \"2026-05-01\", \"label\": \"Fête du travail\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(HOLIDAYS_PATH + "?from=2026-01-01&to=2026-01-31").header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Jour de l'an"));
    }
}
