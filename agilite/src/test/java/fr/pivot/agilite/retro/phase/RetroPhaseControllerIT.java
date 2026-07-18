package fr.pivot.agilite.retro.phase;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.retro.card.RetroCard;
import fr.pivot.agilite.retro.card.RetroCardRepository;
import fr.pivot.agilite.retro.session.RetroPhase;
import fr.pivot.agilite.retro.session.RetroSession;
import fr.pivot.agilite.retro.session.RetroSessionRepository;
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

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link RetroPhaseController} — facilitator-triggered manual contribution
 * close and reveal (US20.1.2a), against the full Spring context (Testcontainers PostgreSQL/Redis).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetroPhaseControllerIT extends AbstractAgiliteIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private RetroSessionRepository sessionRepository;

    @Autowired
    private RetroCardRepository cardRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String facilitatorToken;
    private long facilitatorId;
    private long tenantId;
    private long teamId;
    private String otherTenantToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture facilitator = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        facilitatorToken = facilitator.rawToken();
        facilitatorId = facilitator.userId();
        tenantId = facilitator.tenantId();
        teamId = PlatformAuthTestSupport.seedTeam(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                tenantId, "Team " + UUID.randomUUID());
        PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                teamId, facilitatorId);

        AuthFixture otherTenant = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        otherTenantToken = otherTenant.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions/{id}/contribution/close
    // -------------------------------------------------------------------------

    @Test
    void closeContribution_asFacilitator_transitionsToRevue() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("REVUE"));
    }

    @Test
    void closeContribution_asNonFacilitatorMember_returns403() throws Exception {
        String sessionId = createSession();
        long memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void closeContribution_crossTenant_returns404() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void closeContribution_alreadyClosed_returns409() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/contribution/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    @Test
    void closeContribution_noAuthHeader_returns401() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(post("/agilite/retro/sessions/" + sessionId + "/contribution/close"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions/{id}/reveal
    // -------------------------------------------------------------------------

    @Test
    void reveal_asFacilitatorInRevuePhase_returnsCardsGroupedByColumn() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);
        seedCard(sessionId, "went-well", "Good pace");
        seedCard(sessionId, "went-well", "Great teamwork");
        seedCard(sessionId, "to-improve", "Too many meetings");

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/reveal")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardCount").value(3))
                .andExpect(jsonPath("$.columns['went-well'].length()").value(2))
                .andExpect(jsonPath("$.columns['to-improve'].length()").value(1))
                .andExpect(jsonPath("$.columns['went-well'][0].content").isString());
    }

    @Test
    void reveal_stillInContribution_returns409() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/reveal")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    @Test
    void reveal_asNonFacilitator_returns403() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);
        long memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/reveal")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions/{id}/vote/open
    // -------------------------------------------------------------------------

    @Test
    void openVote_asFacilitatorInRevuePhase_transitionsToVote() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/vote/open")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("VOTE"));
    }

    @Test
    void openVote_asNonFacilitator_returns403() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);
        long memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/vote/open")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void openVote_stillInContribution_returns409() throws Exception {
        String sessionId = createSession();

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/vote/open")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions/{id}/vote/close
    // -------------------------------------------------------------------------

    @Test
    void closeVote_asFacilitatorInVotePhase_transitionsToActionWithRanking() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.VOTE);
        seedCard(sessionId, "went-well", "Good pace");

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/vote/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("ACTION"));
    }

    @Test
    void closeVote_asNonFacilitator_returns403() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.VOTE);
        long memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/vote/close")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void closeVote_stillInRevue_returns409() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.REVUE);

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/vote/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // POST /retro/sessions/{id}/close
    // -------------------------------------------------------------------------

    @Test
    void closeSession_asFacilitatorInActionPhase_transitionsToClosed() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPhase").value("CLOSED"));
    }

    @Test
    void closeSession_asNonFacilitatorMember_returns403() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);
        long memberId = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
        String memberToken = PlatformAuthTestSupport.issueToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                memberId, "active", Instant.now().plusSeconds(3600));

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/close")
                                .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void closeSession_crossTenant_returns404() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/close")
                                .header("Authorization", "Bearer " + otherTenantToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void closeSession_stillInVote_returns409() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.VOTE);

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    @Test
    void closeSession_alreadyClosed_returns409() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.CLOSED);

        mockMvc.perform(
                        post("/agilite/retro/sessions/" + sessionId + "/close")
                                .header("Authorization", "Bearer " + facilitatorToken))
                .andExpect(status().isConflict());
    }

    @Test
    void closeSession_noAuthHeader_returns401() throws Exception {
        String sessionId = createSession();
        advancePhase(sessionId, RetroPhase.ACTION);

        mockMvc.perform(post("/agilite/retro/sessions/" + sessionId + "/close"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createSession() throws Exception {
        MvcResult result = mockMvc.perform(
                        post("/agilite/retro/sessions")
                                .header("Authorization", "Bearer " + facilitatorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"Sprint Retro","format":"START_STOP_CONTINUE","teamId":%d}
                                        """.formatted(teamId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void advancePhase(final String sessionId, final RetroPhase phase) {
        RetroSession session = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
        session.setCurrentPhase(phase);
        sessionRepository.save(session);
    }

    private void seedCard(final String sessionId, final String columnKey, final String content) {
        cardRepository.save(new RetroCard(
                UUID.fromString(sessionId), columnKey, content, false, facilitatorId, Instant.now()));
    }
}
