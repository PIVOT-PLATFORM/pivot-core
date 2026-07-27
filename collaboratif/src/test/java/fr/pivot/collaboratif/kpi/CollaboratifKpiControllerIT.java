package fr.pivot.collaboratif.kpi;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the shared collaboratif-module KPI producer REST surface ({@link
 * CollaboratifKpiController}, EN12.3) exercising the full Spring context against a real
 * PostgreSQL database, same infrastructure as {@code fr.pivot.collaboratif.session.SessionControllerIT}.
 *
 * <p>Supersedes the former, EN19.4-only {@code SessionKpiControllerIT} — this class now also
 * exercises the {@code meetops.*} keys through the exact same {@code /collaboratif/kpi} routes,
 * proving both {@link fr.pivot.collaboratif.session.kpi.SessionKpiService} and {@code
 * fr.pivot.collaboratif.meeting.kpi.MeetopsKpiService} are correctly aggregated by one controller
 * with no {@code Ambiguous mapping} startup failure (the very risk {@link CollaboratifKpiProvider}
 * exists to avoid).
 *
 * <p>Numeric correctness of each domain's aggregate is covered by that domain's own dedicated
 * test ({@code SessionKpiServiceTest}, {@code fr.pivot.collaboratif.meeting.kpi.MeetopsKpiServiceTest}
 * for service-layer logic; {@code fr.pivot.collaboratif.meeting.kpi.MeetopsKpiRepositoryIT} for
 * the MeetOps aggregate's arithmetic) — this class only exercises HTTP wiring: auth, cross-domain
 * dispatch, error mapping, tenant isolation. Paths start with {@code /collaboratif/kpi}, not
 * {@code /api/collaboratif/kpi} — see {@code SessionControllerIT}'s Javadoc for why (MockMvc
 * {@code webAppContextSetup} bypasses the servlet context-path).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CollaboratifKpiControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String KPI_BASE_PATH = "/collaboratif/kpi";
    private static final String SESSIONS_BASE_PATH = "/collaboratif/sessions";
    private static final String MEETINGS_BASE_PATH = "/collaboratif/meetings";

    @Autowired
    private WebApplicationContext wac;

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

    private String createAndStartSession(final AuthFixture owner, final String type) throws Exception {
        String body = mockMvc.perform(post(SESSIONS_BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.authorizationHeader())
                        .content("{\"title\":\"Session Title\",\"type\":\"" + type + "\",\"config\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mockMvc.perform(post(SESSIONS_BASE_PATH + "/" + id + "/start")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isOk());
        return id;
    }

    private void createAndStartMeeting(final AuthFixture owner) throws Exception {
        String body = mockMvc.perform(post(MEETINGS_BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.authorizationHeader())
                        .content("""
                                {"title":"Weekly Sync","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":30,
                                 "agendaItems":[{"title":"Point 1","durationMinutes":10,"type":"INFO"}]}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mockMvc.perform(post(MEETINGS_BASE_PATH + "/" + id + "/start")
                        .header("Authorization", owner.authorizationHeader()))
                .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------------------
    // GET /collaboratif/kpi (list) — cross-domain aggregation
    // -------------------------------------------------------------------------

    @Test
    void list_withoutBearerToken_returns401() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void list_returnsBothDomainsTenDefinitionsTotal() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH).header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(10)))
                .andExpect(jsonPath("$[*].source", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("collaboratif"))));
    }

    @Test
    void list_includesEveryMeetopsKpiKey() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH).header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].kpiKey", org.hamcrest.Matchers.hasItems(
                        "meetops.meetings_run", "meetops.participation_rate",
                        "meetops.action_completion_rate", "meetops.agenda_adherence",
                        "meetops.minutes_shared_rate")));
    }

    // -------------------------------------------------------------------------
    // GET /collaboratif/kpi/{kpiKey} (pull) — error cases, cross-domain dispatch
    // -------------------------------------------------------------------------

    @Test
    void resolve_withoutBearerToken_returns401() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/meetops.meetings_run?scope=tenant"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolve_unknownKpiKey_returns404() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/not_a_domain.not_a_kpi?scope=tenant")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolve_unknownKpiKeyWithMeetopsLikePrefix_returns404NotMisroutedToSession() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/meetops.not_a_kpi?scope=tenant")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolve_scopeNotSupportedByThisKpi_returns400() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/meetops.participation_rate?scope=tenant")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_KPI_SCOPE"));
    }

    @Test
    void resolve_teamScopeWithoutTeamId_returns400() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/meetops.participation_rate?scope=team")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_KPI_SCOPE"));
    }

    @Test
    void resolve_teamScopeForAnotherTenantsTeam_returns404() throws Exception {
        long otherTenantTeamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userB.tenantId(), "Other tenant's team");

        mockMvc.perform(get(KPI_BASE_PATH + "/meetops.participation_rate?scope=team&teamId=" + otherTenantTeamId)
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /collaboratif/kpi/{kpiKey} (pull) — happy path + tenant isolation, both domains
    // -------------------------------------------------------------------------

    @Test
    void resolve_sessionKpi_stillWorksThroughTheSharedController() throws Exception {
        createAndStartSession(userA, "WORDCLOUD");

        mockMvc.perform(get(KPI_BASE_PATH + "/session.sessions_run?scope=tenant")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpiKey").value("session.sessions_run"))
                .andExpect(jsonPath("$.value").value(greaterThanOrEqualTo(1.0)));
    }

    @Test
    void resolve_meetopsMeetingsRun_tenantScope_reflectsStartedMeetingsOfTheCallersTenant() throws Exception {
        createAndStartMeeting(userA);

        mockMvc.perform(get(KPI_BASE_PATH + "/meetops.meetings_run?scope=tenant")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("collaboratif"))
                .andExpect(jsonPath("$.kpiKey").value("meetops.meetings_run"))
                .andExpect(jsonPath("$.unit").value("count"))
                .andExpect(jsonPath("$.scope").isEmpty())
                .andExpect(jsonPath("$.value").value(greaterThanOrEqualTo(1.0)));
    }

    @Test
    void resolve_meetopsMeetingsRun_tenantScope_neverCountsAnotherTenantsMeetings() throws Exception {
        createAndStartMeeting(userA);

        mockMvc.perform(get(KPI_BASE_PATH + "/meetops.meetings_run?scope=tenant")
                        .header("Authorization", userB.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(0.0));
    }
}
