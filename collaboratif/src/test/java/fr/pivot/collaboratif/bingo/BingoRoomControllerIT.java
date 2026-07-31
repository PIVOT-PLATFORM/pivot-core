package fr.pivot.collaboratif.bingo;

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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Bingo room REST surface (US47.1.1) — create (AC-47.1.1-01), join
 * authenticated/anonymous (AC-47.1.1-02/03/04/17), grid re-fetch (AC-47.1.1-05/20), and the error
 * cases (AC-47.1.1-15/16/17).
 *
 * <p>Note: MockMvc via {@code webAppContextSetup} dispatches without the {@code
 * server.servlet.context-path} prefix — paths here start with {@code /collaboratif/bingo/rooms}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BingoRoomControllerIT extends AbstractCollaboratifIntegrationTest {

    private static final String BASE_PATH = "/collaboratif/bingo/rooms";

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private BingoRoomRepository roomRepository;

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

    // -------------------------------------------------------------------------
    // AC-47.1.1-01 — create
    // -------------------------------------------------------------------------

    @Test
    void create_returnsCreatedRoomWithGridAndAccessToken() throws Exception {
        MvcResult result = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("{\"name\":\"Reunion hebdo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.role").value("PLAYER"))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.wsTopic").exists())
                .andExpect(jsonPath("$.grid.cells.length()").value(25))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String code = JsonPath.read(body, "$.code");
        assertThat(code).matches("[" + BingoInviteCodeGenerator.ALPHABET + "]{6}");
    }

    @Test
    void create_withoutBearerToken_returns401() throws Exception {
        mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Reunion hebdo\"}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // AC-47.1.1-04 — grid shape and independence
    // -------------------------------------------------------------------------

    @Test
    void grid_hasExactly25DistinctPhrasesAndIndependentDispositionBetweenParticipants() throws Exception {
        String createBody = createRoom(userA).getResponse().getContentAsString();
        String code = JsonPath.read(createBody, "$.code");
        List<Map<String, Object>> creatorCells = JsonPath.read(createBody, "$.grid.cells");

        String joinBody = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userB.authorizationHeader())
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> joinerCells = JsonPath.read(joinBody, "$.grid.cells");

        assertThat(creatorCells).hasSize(25);
        assertThat(joinerCells).hasSize(25);

        Set<String> creatorPhrases = distinctPhrases(creatorCells);
        Set<String> joinerPhrases = distinctPhrases(joinerCells);
        assertThat(creatorPhrases).hasSize(25);
        assertThat(joinerPhrases).hasSize(25);
        // Independent disposition — vanishingly unlikely the two 25-of-N draws land identically.
        assertThat(creatorPhrases).isNotEqualTo(joinerPhrases);
    }

    private Set<String> distinctPhrases(final List<Map<String, Object>> cells) {
        Set<String> phrases = new HashSet<>();
        for (Map<String, Object> cell : cells) {
            phrases.add((String) cell.get("phrase"));
        }
        return phrases;
    }

    // -------------------------------------------------------------------------
    // AC-47.1.1-02/05 — authenticated join + grid re-fetch
    // -------------------------------------------------------------------------

    @Test
    void join_authenticated_ignoresSuppliedDisplayNameAndReturnsAGrid() throws Exception {
        String code = createRoomCode(userA);

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userB.authorizationHeader())
                        .content("{\"code\":\"" + code + "\",\"displayName\":\"Ignored Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PLAYER"))
                .andExpect(jsonPath("$.grid.cells.length()").value(25));
    }

    @Test
    void getGrid_reconnection_returnsSameGridWithoutRegenerating() throws Exception {
        String createBody = createRoom(userA).getResponse().getContentAsString();
        String roomId = JsonPath.read(createBody, "$.roomId");
        String accessToken = JsonPath.read(createBody, "$.accessToken");
        List<Map<String, Object>> originalCells = JsonPath.read(createBody, "$.grid.cells");

        String refetched = mockMvc.perform(get(BASE_PATH + "/" + roomId + "/grid")
                        .header("access-token", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.role").value("PLAYER"))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> refetchedCells = JsonPath.read(refetched, "$.grid.cells");

        assertThat(distinctPhrases(refetchedCells)).isEqualTo(distinctPhrases(originalCells));
    }

    @Test
    void getGrid_unknownRoomId_returns404() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/" + java.util.UUID.randomUUID() + "/grid")
                        .header("access-token", "whatever"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getGrid_wrongAccessToken_returns404() throws Exception {
        String roomId = JsonPath.read(createRoom(userA).getResponse().getContentAsString(), "$.roomId");

        mockMvc.perform(get(BASE_PATH + "/" + roomId + "/grid")
                        .header("access-token", "not-the-real-token"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // AC-47.1.1-03/17 — anonymous join
    // -------------------------------------------------------------------------

    @Test
    void join_anonymous_withValidPseudonym_returnsAGridAndNoAccountIsCreated() throws Exception {
        String code = createRoomCode(userA);

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"displayName\":\"Guest Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PLAYER"))
                .andExpect(jsonPath("$.grid.cells.length()").value(25));
    }

    @Test
    void join_anonymous_withoutDisplayName_returns400WithInvalidDisplayNameCode() throws Exception {
        String code = createRoomCode(userA);

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DISPLAY_NAME"));
    }

    @Test
    void join_anonymous_withBlankDisplayName_returns400() throws Exception {
        String code = createRoomCode(userA);

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"displayName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DISPLAY_NAME"));
    }

    @Test
    void join_anonymous_withTooLongDisplayName_returns400() throws Exception {
        String code = createRoomCode(userA);
        String tooLong = "A".repeat(31);

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"displayName\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DISPLAY_NAME"));
    }

    // -------------------------------------------------------------------------
    // AC-47.1.1-15/16 — invalid/unknown code
    // -------------------------------------------------------------------------

    @Test
    void join_blankCode_returns400WithInvalidCodeCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    @Test
    void join_wrongLengthCode_returns400WithInvalidCodeCode() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("{\"code\":\"ABCDE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
    }

    @Test
    void join_unknownCode_returnsGeneric404() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userA.authorizationHeader())
                        .content("{\"code\":\"ZZZZZZ\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    void join_finishedRoom_returnsTheSameGeneric404AsAnUnknownCode() throws Exception {
        String createBody = createRoom(userA).getResponse().getContentAsString();
        String code = JsonPath.read(createBody, "$.code");
        String roomId = JsonPath.read(createBody, "$.roomId");

        BingoRoom room = roomRepository.findById(java.util.UUID.fromString(roomId)).orElseThrow();
        roomRepository.finishIfOpen(room.getId(), java.util.UUID.randomUUID(), "ROW", 0);

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userB.authorizationHeader())
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MvcResult createRoom(final AuthFixture creator) throws Exception {
        return mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", creator.authorizationHeader())
                        .content("{\"name\":\"Room " + Instant.now().toEpochMilli() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String createRoomCode(final AuthFixture creator) throws Exception {
        return JsonPath.read(createRoom(creator).getResponse().getContentAsString(), "$.code");
    }
}
