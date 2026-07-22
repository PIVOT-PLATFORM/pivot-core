package fr.pivot.agilite.standup;

import fr.pivot.agilite.AbstractAgiliteIntegrationTest;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport;
import fr.pivot.agilite.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test proving {@link StandupTimerScheduler}'s speaking-timer-based auto-rotation
 * against the full Spring context and a real, running {@code @Scheduled} task (US10.2.1 AC: "when
 * {@code speakingAt + timePerPersonSeconds + extraSeconds} est dépassé, then un scheduler
 * périodique déclenche automatiquement la même transition que {@code POST .../next}").
 *
 * <p>Uses the minimum valid {@code timePerPersonSeconds} (30s) plus a back-dated {@code
 * speakingAt} (see {@link #createAndStartSessionWithShortTimer}) so the deadline has already
 * elapsed without waiting out a real 30-second window, against the much shorter scheduler poll
 * interval configured in {@code application-test.yml} (200ms) — same pattern as {@code
 * RetroPhaseSchedulerIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StandupTimerSchedulerIT extends AbstractAgiliteIntegrationTest {

    private static final String BASE_PATH = "/agilite/standup/sessions";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private StandupSessionRepository sessionRepository;

    @Autowired
    private StandupParticipantRepository participantRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private long teamA;
    private long teamMemberA1;
    private long teamMemberA2;
    private String tokenA1;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();

        AuthFixture fixtureA1 = PlatformAuthTestSupport.seedActiveUserWithTeamAndToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        long tenantA = fixtureA1.tenantId();
        teamA = fixtureA1.teamId();
        teamMemberA1 = fixtureA1.teamMemberId();
        tokenA1 = fixtureA1.rawToken();

        long userA2 = PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantA, true, "Bob", "Martin");
        teamMemberA2 = PlatformAuthTestSupport.seedTeamMember(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), teamA, userA2);
    }

    /**
     * Given a running session whose current speaker's 1-second time is exceeded, when the
     * scheduler next polls, then the rotation to the next participant happens automatically,
     * without any manual {@code next} call.
     */
    @Test
    void speakingTimerExpiry_autoRotatesToNextParticipant() throws Exception {
        String sessionId = createAndStartSessionWithShortTimer();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            StandupSession session = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
            assertThat(session.getCurrentIndex()).isEqualTo(1);
            StandupParticipant participant1 = participantRepository
                    .findBySessionIdOrderByParticipantOrderAsc(UUID.fromString(sessionId))
                    .stream()
                    .filter(p -> p.getParticipantOrder() == 1)
                    .findFirst()
                    .orElseThrow();
            assertThat(participant1.getStatus()).isEqualTo(StandupParticipantStatus.SPEAKING);
        });
    }

    /**
     * Given a manual {@code next} that happens just before the timer would have expired, when the
     * scheduler subsequently polls, then it finds no participant still {@code SPEAKING} from the
     * already-completed turn and performs no further rotation (idempotence, same guard as the
     * double-{@code next} race, US10.2.1 AC).
     */
    @Test
    void manualNextBeforeExpiry_schedulerDoesNotDoubleAdvance() throws Exception {
        String sessionId = createAndStartSessionWithShortTimer();

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/next")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk());

        // Give the scheduler several poll cycles a chance to run (it must find nothing to do).
        Thread.sleep(1500);

        MvcResult result = mockMvc.perform(get(BASE_PATH + "/" + sessionId)
                        .header("Authorization", "Bearer " + tokenA1))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("currentIndex").asInt()).isEqualTo(1);
        assertThat(body.get("participants").get(1).get("status").asText()).isEqualTo("SPEAKING");
    }

    private String createAndStartSessionWithShortTimer() throws Exception {
        MvcResult created = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tokenA1)
                        .content("{\"teamId\": " + teamA + ", \"name\": \"Timed Daily\", "
                                + "\"timePerPersonSeconds\": 30, "
                                + "\"participantTeamMemberIds\": [" + teamMemberA1 + ", " + teamMemberA2 + "]}"))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post(BASE_PATH + "/" + sessionId + "/start")
                        .header("Authorization", "Bearer " + tokenA1))
                .andExpect(status().isOk());

        // timePerPersonSeconds itself must stay within the [30, 1800] DB CHECK constraint
        // (chk_standup_session_time_per_person) — instead, back-date the first participant's
        // speakingAt far enough into the past that its 30s deadline has already elapsed, so the
        // scheduler's 200ms-interval poll (application-test.yml) converges quickly without
        // waiting out a real 30-second window.
        StandupParticipant speaking = participantRepository
                .findBySessionIdOrderByParticipantOrderAsc(UUID.fromString(sessionId))
                .stream()
                .filter(p -> p.getParticipantOrder() == 0)
                .findFirst()
                .orElseThrow();
        ReflectionTestUtils.setField(speaking, "speakingAt", Instant.now().minusSeconds(40));
        participantRepository.save(speaking);

        return sessionId;
    }
}
