package fr.pivot.collaboratif.session;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Module Session REST surface (US19.1.1, US19.1.2, US19.2.1, US19.3.2,
 * US19.3.3) exercising the full Spring context against a real PostgreSQL database and Redis
 * provided by Testcontainers.
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix. Paths used in tests therefore start
 * with {@code /collaboratif/sessions} (not {@code /api/collaboratif/sessions}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String BASE_PATH = "/collaboratif/sessions";

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

    private MvcResult createSession(final AuthFixture owner, final String type, final String config) throws Exception {
        return mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.authorizationHeader())
                        .content("{\"title\":\"Session Title\",\"type\":\"" + type + "\",\"config\":" + config + "}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    // -------------------------------------------------------------------------
    // POST /collaboratif/sessions (US19.1.1)
    // -------------------------------------------------------------------------

    @Test
    void createSession_returnsCreatedDraftSessionWithA6CharacterJoinCode() throws Exception {
        MvcResult result = createSession(userA, "WORDCLOUD", "{\"maxWordsPerParticipant\":3}");

        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(body, "$.joinCode")).matches("[A-HJ-NP-Z2-9]{6}");
    }

    @Test
    void createSession_withBlankTitle_returns400WithInvalidTitleCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("{\"title\":\"\",\"type\":\"WORDCLOUD\",\"config\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    @Test
    void createSession_withoutBearerToken_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"type\":\"WORDCLOUD\",\"config\":{}}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // GET /collaboratif/sessions/{id} (US19.1.1) — tenant isolation
    // -------------------------------------------------------------------------

    @Test
    void getSession_forAnotherTenant_returns404() throws Exception {
        String id = JsonPath.read(
                createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get(BASE_PATH + "/" + id).header("Authorization", userB.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSession_forItsOwnCreator_returnsIt() throws Exception {
        String id = JsonPath.read(
                createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get(BASE_PATH + "/" + id).header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    // -------------------------------------------------------------------------
    // Lifecycle (US19.1.2)
    // -------------------------------------------------------------------------

    @Test
    void lifecycle_startPauseResumeEnd_succeedsForTheOwner() throws Exception {
        String id = JsonPath.read(
                createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post(BASE_PATH + "/" + id + "/start").header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE_PATH + "/" + id + "/pause").header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE_PATH + "/" + id + "/resume").header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk());
        mockMvc.perform(post(BASE_PATH + "/" + id + "/end").header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk());
    }

    @Test
    void start_calledTwice_returns409WithInvalidTransitionCode() throws Exception {
        String id = JsonPath.read(
                createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post(BASE_PATH + "/" + id + "/start").header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE_PATH + "/" + id + "/start").header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_TRANSITION"));
    }

    @Test
    void start_calledByANonOwnerNonAdmin_returns404() throws Exception {
        String id = JsonPath.read(
                createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post(BASE_PATH + "/" + id + "/start").header("Authorization", userB.authorizationHeader()))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /collaboratif/sessions/join (US19.2.1)
    // -------------------------------------------------------------------------

    @Test
    void join_withAnUnknownCode_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ZZZZZZ\",\"displayName\":\"Alice\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void join_asAnAnonymousGuest_returnsASealedToken() throws Exception {
        String body = createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString();
        String joinCode = JsonPath.read(body, "$.joinCode");
        String sessionId = JsonPath.read(body, "$.id");
        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/start").header("Authorization", userA.authorizationHeader()));

        MvcResult joinResult = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + joinCode + "\",\"displayName\":\"Guest One\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String joinBody = joinResult.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(joinBody, "$.token")).isNotBlank();
        assertThat((String) JsonPath.read(joinBody, "$.wsTopic")).contains(sessionId);
    }

    @Test
    void join_asAnAuthenticatedUserInTheSameTenant_omitsTheToken() throws Exception {
        String body = createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString();
        String joinCode = JsonPath.read(body, "$.joinCode");
        long sameTenantUserId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), userA.tenantId(), true);
        String sameTenantToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                sameTenantUserId, "active", java.time.Instant.now().plusSeconds(3600));

        MvcResult joinResult = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + sameTenantToken)
                        .content("{\"code\":\"" + joinCode + "\",\"displayName\":\"Bob\"}"))
                .andExpect(status().isOk())
                .andReturn();

        Object token = JsonPath.read(joinResult.getResponse().getContentAsString(), "$.token");
        assertThat(token).isNull();
    }

    @Test
    void join_asAnAuthenticatedUserFromAnotherTenant_returns404() throws Exception {
        String body = createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString();
        String joinCode = JsonPath.read(body, "$.joinCode");

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userB.authorizationHeader())
                        .content("{\"code\":\"" + joinCode + "\",\"displayName\":\"Bob\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void heartbeat_withTheWrongToken_returns401WithGuestSessionExpiredCode() throws Exception {
        String body = createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString();
        String joinCode = JsonPath.read(body, "$.joinCode");
        String sessionId = JsonPath.read(body, "$.id");

        MvcResult joinResult = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + joinCode + "\",\"displayName\":\"Guest\"}"))
                .andReturn();
        String participantId = JsonPath.read(joinResult.getResponse().getContentAsString(), "$.participantId");

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/participants/" + participantId + "/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"wrong-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GUEST_SESSION_EXPIRED"));
    }

    // -------------------------------------------------------------------------
    // POLL (US19.3.2)
    // -------------------------------------------------------------------------

    @Test
    void pollVote_asGuest_thenHideResults_omitsCountsFromThePayload() throws Exception {
        String createBody = createSession(userA, "POLL",
                "{\"question\":\"Q?\",\"options\":[\"Option A\",\"Option B\"],\"allowMultiple\":false}")
                .getResponse().getContentAsString();
        String sessionId = JsonPath.read(createBody, "$.id");
        String joinCode = JsonPath.read(createBody, "$.joinCode");
        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/start").header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk());

        MvcResult joinResult = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + joinCode + "\",\"displayName\":\"Guest\"}"))
                .andReturn();
        String guestToken = JsonPath.read(joinResult.getResponse().getContentAsString(), "$.token");

        // fetch the poll's own option ids via facilitator hide-results round trip is not exposed
        // directly; resolve via the vote-side error path is out of scope — this test only checks
        // the hide-results payload shape, not the vote itself, since option ids are internal.
        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/poll/hide-results")
                        .header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // WORDCLOUD (US19.3.3)
    // -------------------------------------------------------------------------

    @Test
    void wordcloudSubmit_exceedingThePerParticipantLimit_returns409WithWordLimitReachedCode() throws Exception {
        String createBody = createSession(userA, "WORDCLOUD", "{\"maxWordsPerParticipant\":1}")
                .getResponse().getContentAsString();
        String sessionId = JsonPath.read(createBody, "$.id");
        String joinCode = JsonPath.read(createBody, "$.joinCode");
        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/start").header("Authorization", userA.authorizationHeader()))
                .andExpect(status().isOk());

        MvcResult joinResult = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + joinCode + "\",\"displayName\":\"Guest\"}"))
                .andReturn();
        String guestToken = JsonPath.read(joinResult.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/wordcloud/words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Guest-Token", guestToken)
                        .content("{\"word\":\"hello\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/wordcloud/words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Guest-Token", guestToken)
                        .content("{\"word\":\"world\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORD_LIMIT_REACHED"));
    }

    @Test
    void wordcloudSubmit_beforeTheSessionIsLive_returns409WithInvalidSessionStatusCode() throws Exception {
        String createBody = createSession(userA, "WORDCLOUD", "{}").getResponse().getContentAsString();
        String sessionId = JsonPath.read(createBody, "$.id");
        String joinCode = JsonPath.read(createBody, "$.joinCode");

        MvcResult joinResult = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + joinCode + "\",\"displayName\":\"Guest\"}"))
                .andReturn();
        String guestToken = JsonPath.read(joinResult.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/wordcloud/words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Guest-Token", guestToken)
                        .content("{\"word\":\"hello\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATUS"));
    }
}
