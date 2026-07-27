package fr.pivot.collaboratif.meeting;

import com.jayway.jsonpath.JsonPath;
import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the MeetOps meeting creation REST surface (US12.1.1) exercising the full
 * Spring context against a real PostgreSQL database provided by Testcontainers.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix. Paths used in tests therefore start
 * with {@code /collaboratif/meetings} (not {@code /api/collaboratif/meetings}) — the {@code
 * Location} header value itself is unaffected, since {@link MeetingController} builds it as a
 * literal {@code /api/collaboratif/meetings/{id}} string rather than from the request path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeetingControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String BASE_PATH = "/collaboratif/meetings";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MeetingRepository meetingRepository;

    private MockMvc mockMvc;
    private AuthFixture userA;
    private AuthFixture userB;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        userA = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        userB = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private MvcResult postMeeting(final AuthFixture caller, final String body) throws Exception {
        return mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", caller.authorizationHeader())
                        .content(body))
                .andReturn();
    }

    // -------------------------------------------------------------------------
    // AC1 — creation happy path
    // -------------------------------------------------------------------------

    @Test
    void createMeeting_ac1_returns201WithLocationHeaderAndDraftBody() throws Exception {
        MvcResult result = postMeeting(userA, """
                {"title":"Sprint Review","scheduledAt":"2026-08-01T10:00:00Z",
                 "totalDurationMinutes":60,"agendaItems":[]}""");

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String id = JsonPath.read(body, "$.id");
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/collaboratif/meetings/" + id);
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(body, "$.title")).isEqualTo("Sprint Review");
    }

    // -------------------------------------------------------------------------
    // AC2 — agenda items persisted with position
    // -------------------------------------------------------------------------

    @Test
    void createMeeting_ac2_persistsEachAgendaItemWithOrderedPosition() throws Exception {
        MvcResult result = postMeeting(userA, """
                {"title":"Planning","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":30,
                 "agendaItems":[
                   {"title":"Point A","durationMinutes":10,"type":"INFO"},
                   {"title":"Point B","durationMinutes":20,"type":"DECISION","facilitator":"Alice"}
                 ]}""");

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat((String) JsonPath.read(body, "$.agendaItems[0].title")).isEqualTo("Point A");
        assertThat((Integer) JsonPath.read(body, "$.agendaItems[0].position")).isEqualTo(0);
        assertThat((String) JsonPath.read(body, "$.agendaItems[0].type")).isEqualTo("INFO");
        assertThat((String) JsonPath.read(body, "$.agendaItems[1].title")).isEqualTo("Point B");
        assertThat((Integer) JsonPath.read(body, "$.agendaItems[1].position")).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.agendaItems[1].facilitator")).isEqualTo("Alice");
    }

    // -------------------------------------------------------------------------
    // AC3 — duration reconciliation warning
    // -------------------------------------------------------------------------

    @Test
    void createMeeting_ac3_withMismatchedDurations_returns201WithWarning() throws Exception {
        MvcResult result = postMeeting(userA, """
                {"title":"Mismatch","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":60,
                 "agendaItems":[{"title":"Point A","durationMinutes":10,"type":"INFO"}]}""");

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(body, "$.agendaDurationMismatch.expectedMinutes")).isEqualTo(60);
        assertThat((Integer) JsonPath.read(body, "$.agendaDurationMismatch.sumMinutes")).isEqualTo(10);
        assertThat((Integer) JsonPath.read(body, "$.agendaDurationMismatch.deltaMinutes")).isEqualTo(-50);
    }

    @Test
    void createMeeting_ac3_withMatchingDurations_omitsWarning() throws Exception {
        MvcResult result = postMeeting(userA, """
                {"title":"Matched","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":10,
                 "agendaItems":[{"title":"Point A","durationMinutes":10,"type":"INFO"}]}""");

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body).doesNotContain("agendaDurationMismatch");
    }

    // -------------------------------------------------------------------------
    // AC4 — empty/absent agenda allowed, no warning
    // -------------------------------------------------------------------------

    @Test
    void createMeeting_ac4_withEmptyAgendaItems_returns201WithoutWarning() throws Exception {
        MvcResult result = postMeeting(userA, """
                {"title":"No agenda","scheduledAt":"2026-08-01T10:00:00Z",
                 "totalDurationMinutes":30,"agendaItems":[]}""");

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body).doesNotContain("agendaDurationMismatch");
        assertThat((java.util.List<?>) JsonPath.read(body, "$.agendaItems")).isEmpty();
    }

    @Test
    void createMeeting_ac4_withoutAgendaItemsField_returns201() throws Exception {
        MvcResult result = postMeeting(userA, """
                {"title":"No agenda field","scheduledAt":"2026-08-01T10:00:00Z",
                 "totalDurationMinutes":30}""");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("agendaDurationMismatch");
    }

    // -------------------------------------------------------------------------
    // AC5 — team attachment
    // -------------------------------------------------------------------------

    @Test
    void createMeeting_ac5_withValidTeamId_attachesTheMeetingToTheTeam() throws Exception {
        long teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userA.tenantId(), "Team Alpha " + UUID.randomUUID());

        MvcResult result = postMeeting(userA, """
                {"title":"Team meeting","scheduledAt":"2026-08-01T10:00:00Z",
                 "totalDurationMinutes":30,"teamId":%d}""".formatted(teamId));

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat((Long) ((Number) JsonPath.read(body, "$.teamId")).longValue()).isEqualTo(teamId);
    }

    @Test
    void createMeeting_ac5_withoutTeamId_isPersonal() throws Exception {
        MvcResult result = postMeeting(userA, """
                {"title":"Personal meeting","scheduledAt":"2026-08-01T10:00:00Z",
                 "totalDurationMinutes":30}""");

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        Object teamId = JsonPath.read(body, "$.teamId");
        assertThat(teamId).isNull();
    }

    // -------------------------------------------------------------------------
    // AC6 — validation errors (400, problem+json, nothing persisted)
    // -------------------------------------------------------------------------

    @Test
    void createMeeting_ac6_withBlankTitle_returns400WithInvalidTitleCodeAndPersistsNothing() throws Exception {
        long before = meetingRepository.count();

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":30}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));

        assertThat(meetingRepository.count()).isEqualTo(before);
    }

    @Test
    void createMeeting_ac6_withTitleOver200Chars_returns400WithInvalidTitleCode() throws Exception {
        String longTitle = "A".repeat(201);
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"%s","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":30}"""
                                .formatted(longTitle)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    @Test
    void createMeeting_ac6_withMissingScheduledAt_returns400WithInvalidScheduledAtCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"Missing date","totalDurationMinutes":30}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULED_AT"));
    }

    @Test
    void createMeeting_ac6_withNonIso8601ScheduledAt_returns400() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"Bad date","scheduledAt":"not-a-date","totalDurationMinutes":30}"""))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE));
    }

    @Test
    void createMeeting_ac6_withZeroTotalDuration_returns400WithInvalidTotalDurationCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"Zero duration","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOTAL_DURATION_MINUTES"));
    }

    @Test
    void createMeeting_ac6_withTotalDurationOver1440_returns400WithInvalidTotalDurationCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"Too long","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":1441}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TOTAL_DURATION_MINUTES"));
    }

    @Test
    void createMeeting_ac6_withAgendaItemZeroDuration_returns400AndPersistsNothing() throws Exception {
        long before = meetingRepository.count();

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"Bad item","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":30,
                                 "agendaItems":[{"title":"Point A","durationMinutes":0,"type":"INFO"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENDA_ITEM_DURATION"));

        assertThat(meetingRepository.count()).isEqualTo(before);
    }

    @Test
    void createMeeting_ac6_withAgendaItemInvalidType_returns400AndPersistsNothing() throws Exception {
        long before = meetingRepository.count();

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"Bad type","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":30,
                                 "agendaItems":[{"title":"Point A","durationMinutes":10,"type":"URGENT"}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENDA_ITEM_TYPE"));

        assertThat(meetingRepository.count()).isEqualTo(before);
    }

    // -------------------------------------------------------------------------
    // AC7 — teamId cross-tenant / unknown → 404, anti-enumeration
    // -------------------------------------------------------------------------

    @Test
    void createMeeting_ac7_withUnknownTeamId_returns404AndPersistsNothing() throws Exception {
        long before = meetingRepository.count();

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"Unknown team","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":30,"teamId":999999}"""))
                .andExpect(status().isNotFound());

        assertThat(meetingRepository.count()).isEqualTo(before);
    }

    @Test
    void createMeeting_ac7_withAnotherTenantsTeamId_returns404AndPersistsNothing() throws Exception {
        long otherTenantTeamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userB.tenantId(), "Team Beta " + UUID.randomUUID());
        long before = meetingRepository.count();

        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("""
                                {"title":"Cross tenant","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":30,"teamId":%d}""".formatted(otherTenantTeamId)))
                .andExpect(status().isNotFound());

        assertThat(meetingRepository.count()).isEqualTo(before);
    }

    // -------------------------------------------------------------------------
    // AC8/AC9 — tenant/auth security
    // -------------------------------------------------------------------------

    @Test
    void createMeeting_ac8_withoutBearerToken_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"No auth","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":30}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createMeeting_ac8_ignoresTenantIdAndOwnerIdFromThePayload() throws Exception {
        MvcResult result = postMeeting(userA, """
                {"title":"Spoof attempt","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":30,
                 "tenantId":999999,"ownerId":999999,"createdBy":999999}""");

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        String id = JsonPath.read(body, "$.id");
        Meeting persisted = meetingRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(persisted.getTenantId()).isEqualTo(userA.tenantId());
        assertThat(persisted.getCreatedBy()).isEqualTo(userA.userId());
    }

    @Test
    void createMeeting_ac9_tenantIsolation_meetingIsPersistedUnderCallersOwnTenantOnly() throws Exception {
        MvcResult resultA = postMeeting(userA, """
                {"title":"Tenant A meeting","scheduledAt":"2026-08-01T10:00:00Z","totalDurationMinutes":30}""");
        String idA = JsonPath.read(resultA.getResponse().getContentAsString(), "$.id");

        Meeting persisted = meetingRepository.findById(UUID.fromString(idA)).orElseThrow();
        assertThat(persisted.getTenantId()).isEqualTo(userA.tenantId());
        assertThat(persisted.getTenantId()).isNotEqualTo(userB.tenantId());
    }
}
