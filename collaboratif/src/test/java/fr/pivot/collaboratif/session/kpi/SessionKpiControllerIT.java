package fr.pivot.collaboratif.session.kpi;

import com.jayway.jsonpath.JsonPath;
import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import fr.pivot.core.team.Team;
import fr.pivot.core.team.TeamRepository;
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
 * Integration tests for the Session live KPI producer REST surface (EN19.4) exercising the full
 * Spring context against a real PostgreSQL database, same infrastructure as {@link
 * fr.pivot.collaboratif.session.SessionControllerIT}.
 *
 * <p>Numeric correctness of the aggregate itself is covered by {@link SessionKpiServiceTest}
 * (mocked repository) — this class only exercises HTTP wiring: auth, error mapping, tenant
 * isolation. Paths start with {@code /collaboratif/kpi}, not {@code /api/collaboratif/kpi} — see
 * {@code SessionControllerIT}'s Javadoc for why (MockMvc {@code webAppContextSetup} bypasses the
 * servlet context-path).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionKpiControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String KPI_BASE_PATH = "/collaboratif/kpi";
    private static final String SESSIONS_BASE_PATH = "/collaboratif/sessions";

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private TeamRepository teamRepository;

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

    // -------------------------------------------------------------------------
    // GET /collaboratif/kpi (list)
    // -------------------------------------------------------------------------

    @Test
    void list_withoutBearerToken_returns401() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void list_returnsAllFiveDefinitions() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH).header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].source").value("collaboratif"));
    }

    // -------------------------------------------------------------------------
    // GET /collaboratif/kpi/{kpiKey} (pull) — error cases
    // -------------------------------------------------------------------------

    @Test
    void resolve_withoutBearerToken_returns401() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/session.sessions_run?scope=tenant"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolve_unknownKpiKey_returns404() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/session.not_a_kpi?scope=tenant")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolve_scopeNotSupportedByThisKpi_returns400() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/session.avg_participants?scope=tenant")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_KPI_SCOPE"));
    }

    @Test
    void resolve_teamScopeWithoutTeamId_returns400() throws Exception {
        mockMvc.perform(get(KPI_BASE_PATH + "/session.avg_participants?scope=team")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_KPI_SCOPE"));
    }

    @Test
    void resolve_teamScopeForAnotherTenantsTeam_returns404() throws Exception {
        Team otherTenantTeam = teamRepository.save(new Team(userB.tenantId(), "Other tenant's team"));

        mockMvc.perform(get(KPI_BASE_PATH + "/session.avg_participants?scope=team&teamId=" + otherTenantTeam.getId())
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /collaboratif/kpi/{kpiKey} (pull) — happy path + tenant isolation
    // -------------------------------------------------------------------------

    @Test
    void resolve_tenantScope_reflectsLaunchedSessionsOfTheCallersTenant() throws Exception {
        createAndStartSession(userA, "WORDCLOUD");

        mockMvc.perform(get(KPI_BASE_PATH + "/session.sessions_run?scope=tenant")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpiKey").value("session.sessions_run"))
                .andExpect(jsonPath("$.unit").value("count"))
                .andExpect(jsonPath("$.value").value(greaterThanOrEqualTo(1.0)));
    }

    @Test
    void resolve_tenantScope_neverCountsAnotherTenantsSessions() throws Exception {
        createAndStartSession(userA, "WORDCLOUD");

        mockMvc.perform(get(KPI_BASE_PATH + "/session.sessions_run?scope=tenant")
                        .header("Authorization", userB.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(0.0));
    }
}
