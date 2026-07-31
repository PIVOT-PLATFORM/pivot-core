package fr.pivot.collaboratif.bingo.ws;

import com.jayway.jsonpath.JsonPath;
import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the Bingo real-time STOMP channel (US47.1.1) — SUBSCRIBE authorization
 * (AC-47.1.1-06, SEC-01), marking (AC-47.1.1-07/08/09), victory detection
 * (AC-47.1.1-10/11/12), the error cases (AC-47.1.1-14/18/19), and the raw-payload non-leak proof
 * (SEC-04).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BingoWsIT extends AbstractCollaboratifIntegrationTest {

    private static final String BASE_PATH = "/collaboratif/bingo/rooms";

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private final List<StompSession> openSessions = new ArrayList<>();

    @AfterEach
    void disconnectAll() {
        for (StompSession session : openSessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        openSessions.clear();
    }

    // -------------------------------------------------------------------------
    // AC-47.1.1-06 / SEC-01 — SUBSCRIBE authorization
    // -------------------------------------------------------------------------

    @Test
    void subscribe_withoutAccessToken_neverReceivesBroadcasts() throws Exception {
        RoomFixture room = createRoom();
        StompSession session = connectAs(room.creator());
        CompletableFuture<String> future = new CompletableFuture<>();
        session.subscribe(room.wsTopic(), stringHandler(future)); // no access-token header

        Thread.sleep(150);
        session.send(BingoRoomDestinations.APP_ROOM_PREFIX + room.roomId() + "/mark",
                Map.of("cellIndex", 0, "marked", true));

        assertThatExceptionOfType(TimeoutException.class).isThrownBy(() -> future.get(2, TimeUnit.SECONDS));
    }

    @Test
    void subscribe_withValidAccessToken_receivesParticipantJoinedWhenAnotherJoins() throws Exception {
        RoomFixture room = createRoom();
        StompSession session = connectAs(room.creator());
        CompletableFuture<String> future = new CompletableFuture<>();
        subscribeWithToken(session, room.wsTopic(), room.creatorAccessToken(), future);
        Thread.sleep(150);

        AuthFixture joiner = seedAuth();
        joinRoom(joiner, room.code());

        String frame = future.get(5, TimeUnit.SECONDS);
        assertThat(frame).contains("\"type\":\"PARTICIPANT_JOINED\"");
        assertThat(frame).contains("\"playerCount\":2");
    }

    // -------------------------------------------------------------------------
    // AC-47.1.1-07/08/09 — marking, aggregate-only payload, idempotence
    // -------------------------------------------------------------------------

    @Test
    void mark_thenUnmark_reflectsCurrentCountNeverDoubleCountingAndNeverLeaksCellOrPhrase() throws Exception {
        RoomFixture room = createRoom();
        StompSession session = connectAs(room.creator());
        CompletableFuture<String> first = new CompletableFuture<>();
        subscribeWithToken(session, room.wsTopic(), room.creatorAccessToken(), first);
        Thread.sleep(150);

        sendMark(session, room, 0, true);
        String frame1 = first.get(5, TimeUnit.SECONDS);
        assertThat(frame1).contains("\"type\":\"CELL_MARKED\"").contains("\"markedCount\":1");
        // SEC-04 — the raw payload never carries the cell index or the phrase text.
        assertThat(frame1).doesNotContain("cellIndex").doesNotContain("phrase");
        for (String phrase : room.creatorPhrases()) {
            assertThat(frame1).doesNotContain(phrase);
        }

        CompletableFuture<String> second = new CompletableFuture<>();
        subscribeWithToken(session, room.wsTopic(), room.creatorAccessToken(), second);
        Thread.sleep(150);
        sendMark(session, room, 0, false);
        String frame2 = second.get(5, TimeUnit.SECONDS);
        assertThat(frame2).contains("\"markedCount\":0");

        CompletableFuture<String> third = new CompletableFuture<>();
        subscribeWithToken(session, room.wsTopic(), room.creatorAccessToken(), third);
        Thread.sleep(150);
        sendMark(session, room, 0, true);
        sendMark(session, room, 0, true);
        String frame3 = third.get(5, TimeUnit.SECONDS);
        assertThat(frame3).contains("\"markedCount\":1");
    }

    // -------------------------------------------------------------------------
    // AC-47.1.1-10/11/12 — victory detection, atomic finish, no second bingo
    // -------------------------------------------------------------------------

    @Test
    void completingARow_broadcastsBingoAndFinishesTheRoom_thenRejectsFurtherMarks() throws Exception {
        RoomFixture room = createRoom();
        StompSession session = connectAs(room.creator());
        CompletableFuture<String> bingoFuture = new CompletableFuture<>();
        session.subscribe(room.wsTopic(), new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                String text = new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8);
                if (text.contains("\"type\":\"BINGO\"") && !bingoFuture.isDone()) {
                    bingoFuture.complete(text);
                }
            }
        });
        Thread.sleep(150);

        // Row 0 is cell indices 0..4.
        for (int i = 0; i < 5; i++) {
            sendMark(session, room, i, true);
        }

        String bingoFrame = bingoFuture.get(5, TimeUnit.SECONDS);
        assertThat(bingoFrame).contains("\"line\":{\"kind\":\"ROW\",\"index\":0}");
        assertThat(bingoFrame).doesNotContain("cellIndex").doesNotContain("phrase");

        // AC-47.1.1-11/19 — the room is now FINISHED; any further mark is rejected.
        CompletableFuture<String> errorFuture = new CompletableFuture<>();
        session.subscribe("/user/queue/errors", stringHandler(errorFuture));
        Thread.sleep(150);
        sendMark(session, room, 6, true);
        String errorFrame = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(errorFrame).contains("\"code\":\"ROOM_FINISHED\"");
    }

    // -------------------------------------------------------------------------
    // AC-47.1.1-18 — invalid cell index rejected, never persisted/broadcast
    // -------------------------------------------------------------------------

    @Test
    void invalidCellIndex_isRejectedOnTheUserQueueWithoutBroadcast() throws Exception {
        RoomFixture room = createRoom();
        StompSession session = connectAs(room.creator());
        CompletableFuture<String> broadcastFuture = new CompletableFuture<>();
        CompletableFuture<String> errorFuture = new CompletableFuture<>();
        subscribeWithToken(session, room.wsTopic(), room.creatorAccessToken(), broadcastFuture);
        session.subscribe("/user/queue/errors", stringHandler(errorFuture));
        Thread.sleep(150);

        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination(BingoRoomDestinations.APP_ROOM_PREFIX + room.roomId() + "/mark");
        sendHeaders.set("access-token", room.creatorAccessToken());
        session.send(sendHeaders, Map.of("cellIndex", 99, "marked", true));

        String errorFrame = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(errorFrame).contains("\"code\":\"INVALID_CELL\"");
        assertThatExceptionOfType(TimeoutException.class).isThrownBy(() -> broadcastFuture.get(1, TimeUnit.SECONDS));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void sendMark(final StompSession session, final RoomFixture room, final int cellIndex, final boolean marked) {
        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination(BingoRoomDestinations.APP_ROOM_PREFIX + room.roomId() + "/mark");
        sendHeaders.set("access-token", room.creatorAccessToken());
        session.send(sendHeaders, Map.of("cellIndex", cellIndex, "marked", marked));
    }

    private void subscribeWithToken(
            final StompSession session, final String topic, final String accessToken, final CompletableFuture<String> future) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(topic);
        headers.set("access-token", accessToken);
        session.subscribe(headers, stringHandler(future));
    }

    private StompFrameHandler stringHandler(final CompletableFuture<String> future) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                if (!future.isDone()) {
                    future.complete(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        };
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/api/collaboratif/ws/collaboratif";
    }

    private WebSocketStompClient createClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        return client;
    }

    private StompSession connectAs(final AuthFixture user) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", user.authorizationHeader());
        StompSession session = createClient()
                .connectAsync(wsUrl(), new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
                })
                .get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    private AuthFixture seedAuth() throws Exception {
        return PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private RoomFixture createRoom() throws Exception {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        }
        AuthFixture creator = seedAuth();
        String body = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", creator.authorizationHeader())
                        .content("{\"name\":\"WS Room\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String roomId = JsonPath.read(body, "$.roomId");
        String code = JsonPath.read(body, "$.code");
        String wsTopic = JsonPath.read(body, "$.wsTopic");
        String accessToken = JsonPath.read(body, "$.accessToken");
        List<Map<String, Object>> cells = JsonPath.read(body, "$.grid.cells");
        List<String> phrases = cells.stream().map(c -> (String) c.get("phrase")).toList();
        return new RoomFixture(creator, roomId, code, wsTopic, accessToken, phrases);
    }

    private void joinRoom(final AuthFixture joiner, final String code) throws Exception {
        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", joiner.authorizationHeader())
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());
    }

    private record RoomFixture(
            AuthFixture creator, String roomId, String code, String wsTopic,
            String creatorAccessToken, List<String> creatorPhrases) {
    }
}
