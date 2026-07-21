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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link StandupSessionController} exercising the full Spring context
 * against a real PostgreSQL database and Redis provided by Testcontainers
 * (US10.1.1/US10.1.2/US10.2.2).
 *
 * <p>Covers the backlog AC: creation (default/explicit {@code timePerPersonSeconds}, order
 * preservation, name resolution), list/get/delete, the full start/next/end lifecycle, the
 * double-{@code next} idempotence guarantee, skip/extend/reorder, every documented error case,
 * and tenant/team-membership isolation (404, never 403, on every endpoint).
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches against the servlet path directly,
 * without the {@code server.servlet.context-path} prefix — paths here start with
 * {@code /agilite/standup/sessions}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StandupSessionControllerIT extends AbstractAgiliteIntegrationTest {

    private static final String BASE_PATH = "/agilite/standup/sessions";

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long tenantA;
    private long teamA;
    private long teamMemberA1;
    private long teamMemberA2;
    private String tokenA1;

    private long teamB;
    private String tokenB;

    /**
     * Sets up MockMvc and seeds two distinct tenants (A with two team members, B with one) plus
     * bearer tokens before each test.
     */
    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA1 = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        tenantA = fixtureA1.tenantId();
        teamA = fixtureA1.teamId();
        teamMemberA1 = fixtureA1.teamMemberId();
        tokenA1 = fixtureA1.rawToken();

        long userA2 = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true, "Bob", "Martin");
        teamMemberA2 = PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA, userA2);

        AuthFixture fixtureB = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        teamB = fixtureB.teamId();
        tokenB = fixtureB.rawToken();
    }

    // -------------------------------------------------------------------------
    // POST /standup/sessions
    // -------------------------------------------------------------------------

    @Test
    void create_withDefaultTimePerPerson_returns201WithDefault120AndPreservedOrder() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Daily équipe A\", "
                                + "\"participantTeamMemberIds\": [" + teamMemberA2 + ", " + teamMemberA1 + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.currentIndex").value(0))
                .andExpect(jsonPath("$.timePerPersonSeconds").value(120))
                .andExpect(jsonPath("$.participants.length()").value(2))
                .andExpect(jsonPath("$.participants[0].teamMemberId").value(teamMemberA2))
                .andExpect(jsonPath("$.participants[0].order").value(0))
                .andExpect(jsonPath("$.participants[0].status").value("WAITING"))
                .andExpect(jsonPath("$.participants[0].name").value("Bob Martin"))
                .andExpect(jsonPath("$.participants[1].teamMemberId").value(teamMemberA1))
                .andExpect(jsonPath("$.participants[1].order").value(1));
    }

    @Test
    void create_withExplicitTimePerPerson_returns201WithThatValue() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Daily\", \"timePerPersonSeconds\": 90, "
                                + "\"participantTeamMemberIds\": [" + teamMemberA1 + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timePerPersonSeconds").value(90));
    }

    @Test
    void create_withBlankName_returns400WithInvalidNameCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"\", "
                                + "\"participantTeamMemberIds\": [" + teamMemberA1 + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NAME"));
    }

    @Test
    void create_withEmptyParticipants_returns400WithEmptyParticipantsCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Vide\", \"participantTeamMemberIds\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_PARTICIPANTS"));
    }

    @Test
    void create_withTimePerPersonOutOfRange_returns400WithInvalidTimePerPersonCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"X\", \"timePerPersonSeconds\": 5, "
                                + "\"participantTeamMemberIds\": [" + teamMemberA1 + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_PER_PERSON"));
    }

    @Test
    void create_withParticipantNotInTeam_returns400WithInvalidParticipantCode() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"teamId\": " + teamB + ", \"name\": \"Cross\", "
                                + "\"participantTeamMemberIds\": [" + teamMemberA1 + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPANT"));
    }

    @Test
    void create_forTeamOfAnotherTenant_returns404() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamB + ", \"name\": \"X\", "
                                + "\"participantTeamMemberIds\": [" + teamMemberA1 + "]}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // GET /standup/sessions, GET /standup/sessions/{id}
    // -------------------------------------------------------------------------

    @Test
    void list_returnsAccessibleSessionsFilteredByTeamAndStatus() throws Exception {
        createSessionFor(tokenA1, teamA, "Daily 1", teamMemberA1);
        createSessionFor(tokenA1, teamA, "Daily 2", teamMemberA1);

        mockMvc.perform(get(BASE_PATH).param("teamId", String.valueOf(teamA))
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get(BASE_PATH).param("teamId", String.valueOf(teamA)).param("status", "RUNNING")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void list_nonMemberOfTeam_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH).param("teamId", String.valueOf(teamA))
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void findById_crossTenant_returns404() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);

        mockMvc.perform(get(BASE_PATH + "/" + sessionId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void findById_unknownId_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // DELETE /standup/sessions/{id}
    // -------------------------------------------------------------------------

    @Test
    void delete_pendingSession_returns204AndSessionIsGone() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "À supprimer", teamMemberA1);

        mockMvc.perform(delete(BASE_PATH + "/" + sessionId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(BASE_PATH + "/" + sessionId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_runningSession_returns409() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "En cours", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(delete(BASE_PATH + "/" + sessionId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isConflict());
    }

    @Test
    void delete_crossTenant_returns404() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Titre", teamMemberA1);

        mockMvc.perform(delete(BASE_PATH + "/" + sessionId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /standup/sessions/{id}/start
    // -------------------------------------------------------------------------

    @Test
    void start_pendingSession_returns200WithFirstParticipantSpeaking() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1, teamMemberA2);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.participants[0].status").value("SPEAKING"))
                .andExpect(jsonPath("$.participants[0].speakingAt").exists())
                .andExpect(jsonPath("$.participants[1].status").value("WAITING"));
    }

    @Test
    void start_alreadyRunningSession_returns409WithInvalidSessionStatusCode() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATUS"));
    }

    @Test
    void start_crossTenant_returns404() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /standup/sessions/{id}/next, /end — rotation, idempotence, session end
    // -------------------------------------------------------------------------

    @Test
    void next_rotatesToSecondParticipant() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1, teamMemberA2);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/next")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentIndex").value(1))
                .andExpect(jsonPath("$.participants[0].status").value("DONE"))
                .andExpect(jsonPath("$.participants[0].doneSpeaking").exists())
                .andExpect(jsonPath("$.participants[1].status").value("SPEAKING"));
    }

    @Test
    void next_onLastParticipant_endsSession() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/next")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.endedAt").exists())
                .andExpect(jsonPath("$.participants[0].status").value("DONE"));
    }

    @Test
    void next_racedConcurrently_neverSkipsOrDuplicatesATurn() throws Exception {
        // 8 participants, 5 concurrent `next` calls fired together at a real Testcontainers
        // Postgres via a CountDownLatch-released thread pool (same technique as
        // RetroVoteConcurrencyIT). Whatever the true interleaving turns out to be — some calls
        // may genuinely race on the very same SPEAKING participant, others may land after an
        // earlier one already committed — the atomic finishIfSpeaking guard
        // (StandupParticipantRepository) must keep the rotation strictly sequential: no
        // participant ever reaches DONE without first passing through SPEAKING, at most one
        // participant is SPEAKING at a time, and no participant is left stranded WAITING behind
        // one that is already DONE. A broken (non-atomic) guard would corrupt exactly this
        // invariant under contention — e.g. two racing calls both advancing past the same
        // participant, silently skipping the next one's turn.
        int participantCount = 8;
        int concurrentNextCalls = 5;
        long[] teamMemberIds = new long[participantCount];
        teamMemberIds[0] = teamMemberA1;
        teamMemberIds[1] = teamMemberA2;
        for (int i = 2; i < participantCount; i++) {
            long userId = PlatformAuthTestSupport.seedUser(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                    tenantA, true, "First" + i, "Last" + i);
            teamMemberIds[i] = PlatformAuthTestSupport.seedTeamMember(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA, userId);
        }
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberIds);
        startSession(tokenA1, sessionId);

        ExecutorService executor = Executors.newFixedThreadPool(concurrentNextCalls);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentNextCalls; i++) {
                futures.add(executor.submit(nextCallRacer(sessionId, go)));
            }
            go.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(200);
            }
        } finally {
            executor.shutdown();
        }

        MvcResult afterRace = mockMvc.perform(get(BASE_PATH + "/" + sessionId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        JsonNode body = objectMapper.readTree(afterRace.getResponse().getContentAsString());
        JsonNode participants = body.get("participants");

        int speakingCount = 0;
        boolean seenNonDone = false;
        for (JsonNode participant : participants) {
            String participantStatus = participant.get("status").asText();
            if (participantStatus.equals("SPEAKING")) {
                speakingCount++;
                seenNonDone = true;
            } else if (participantStatus.equals("WAITING")) {
                seenNonDone = true;
            } else {
                // DONE — must never appear after a WAITING/SPEAKING participant (no gaps/skips).
                assertThat(seenNonDone).as("a DONE participant appeared after a non-DONE one").isFalse();
            }
        }
        assertThat(speakingCount).as("at most one participant SPEAKING at a time").isLessThanOrEqualTo(1);
        if (speakingCount == 0) {
            assertThat(body.get("status").asText()).isEqualTo("DONE");
        } else {
            assertThat(body.get("status").asText()).isEqualTo("RUNNING");
        }
    }

    private Callable<Integer> nextCallRacer(final String sessionId, final CountDownLatch go) {
        return () -> {
            go.await();
            return mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/next")
                            .header("Authorization", "Bearer " + tokenA1))
                    .andReturn().getResponse().getStatus();
        };
    }

    @Test
    void next_onNonRunningSession_returns409() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/next")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATUS"));
    }

    @Test
    void next_crossTenant_returns404() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/next")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void end_runningSession_returns200WithDoneStatus() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1, teamMemberA2);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/end")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.endedAt").exists())
                .andExpect(jsonPath("$.participants[0].status").value("DONE"));
    }

    @Test
    void end_onNonRunningSession_returns409() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/end")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATUS"));
    }

    @Test
    void end_crossTenant_returns404() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/end")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /standup/sessions/{id}/skip (US10.2.2)
    // -------------------------------------------------------------------------

    @Test
    void skip_currentSpeaker_marksSkippedAndAdvances() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1, teamMemberA2);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/skip")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.participants[1].status").value("SPEAKING"));
    }

    @Test
    void skip_onNonRunningSession_returns409() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/skip")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATUS"));
    }

    @Test
    void skip_crossTenant_returns404() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/skip")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // POST /standup/sessions/{id}/extend (US10.2.2)
    // -------------------------------------------------------------------------

    @Test
    void extend_currentSpeaker_incrementsExtraSecondsCumulatively() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"seconds\": 30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[0].extraSeconds").value(30));

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"seconds\": 60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[0].extraSeconds").value(90));
    }

    @Test
    void extend_withInvalidSeconds_returns400WithInvalidExtendSecondsCode() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"seconds\": 45}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXTEND_SECONDS"));
    }

    @Test
    void extend_onNonRunningSession_returns409() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"seconds\": 30}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_STATUS"));
    }

    @Test
    void extend_crossTenant_returns404() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1);
        startSession(tokenA1, sessionId);

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"seconds\": 30}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // PUT /standup/sessions/{id}/participants/reorder (US10.2.2)
    // -------------------------------------------------------------------------

    @Test
    void reorder_waitingTail_rewritesOrder() throws Exception {
        long userA3 = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true, "Cara", "Dune");
        long teamMemberA3 = PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA, userA3);

        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1, teamMemberA2, teamMemberA3);
        startSession(tokenA1, sessionId);
        String participantIdOrder1 = participantIdAtOrder(sessionId, 1);
        String participantIdOrder2 = participantIdAtOrder(sessionId, 2);

        mockMvc.perform(put(BASE_PATH + "/" + sessionId + "/participants/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"participantIds\": [\"" + participantIdOrder2 + "\", \"" + participantIdOrder1 + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[1].id").value(participantIdOrder2))
                .andExpect(jsonPath("$.participants[2].id").value(participantIdOrder1));
    }

    @Test
    void reorder_withMismatchedParticipantSet_returns400WithInvalidReorderCode() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1, teamMemberA2);
        startSession(tokenA1, sessionId);

        mockMvc.perform(put(BASE_PATH + "/" + sessionId + "/participants/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"participantIds\": [\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REORDER"));
    }

    @Test
    void reorder_crossTenant_returns404() throws Exception {
        String sessionId = createSessionFor(tokenA1, teamA, "Daily", teamMemberA1, teamMemberA2);
        startSession(tokenA1, sessionId);

        mockMvc.perform(put(BASE_PATH + "/" + sessionId + "/participants/reorder")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenB)
                        .content("{\"participantIds\": []}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createSessionFor(
            final String token, final long teamId, final String name, final long... teamMemberIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < teamMemberIds.length; i++) {
            if (i > 0) {
                ids.append(", ");
            }
            ids.append(teamMemberIds[i]);
        }
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"teamId\": " + teamId + ", \"name\": \"" + name + "\", "
                                + "\"participantTeamMemberIds\": [" + ids + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("id").asText();
    }

    private void startSession(final String token, final String sessionId) throws Exception {
        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String participantIdAtOrder(final String sessionId, final int order) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE_PATH + "/" + sessionId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("participants").get(order).get("id").asText();
    }
}
