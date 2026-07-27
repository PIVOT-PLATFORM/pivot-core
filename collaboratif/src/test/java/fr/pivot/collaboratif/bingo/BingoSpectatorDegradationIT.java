package fr.pivot.collaboratif.bingo;

import com.jayway.jsonpath.JsonPath;
import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.bingo.ws.BingoRoomDestinations;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the maxPlayers threshold and spectator degradation (US47.1.1,
 * AC-47.1.1-13/14) — a dedicated {@code max-players=1} override so the second join in every test
 * here deterministically lands as a {@code SPECTATOR}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BingoSpectatorDegradationIT extends AbstractCollaboratifIntegrationTest {

    private static final String BASE_PATH = "/collaboratif/bingo/rooms";

    @DynamicPropertySource
    static void maxPlayersOverride(final DynamicPropertyRegistry registry) {
        registry.add("pivot.collaboratif.bingo.room.max-players", () -> "1");
    }

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

    @Test
    void secondJoiner_isAdmittedAsSpectator_neverA4xx() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        AuthFixture creator = seedAuth();
        AuthFixture second = seedAuth();

        String createBody = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", creator.authorizationHeader())
                        .content("{\"name\":\"Full room\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(createBody, "$.code");

        mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", second.authorizationHeader())
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SPECTATOR"))
                .andExpect(jsonPath("$.grid").doesNotExist());
    }

    @Test
    void spectator_attemptingToMark_isRejectedWithoutBroadcast() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        AuthFixture creator = seedAuth();
        AuthFixture spectatorUser = seedAuth();

        String createBody = mockMvc.perform(post(BASE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", creator.authorizationHeader())
                        .content("{\"name\":\"Full room\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(createBody, "$.code");
        String roomId = JsonPath.read(createBody, "$.roomId");
        String wsTopic = JsonPath.read(createBody, "$.wsTopic");

        String joinBody = mockMvc.perform(post(BASE_PATH + "/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", spectatorUser.authorizationHeader())
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String spectatorAccessToken = JsonPath.read(joinBody, "$.accessToken");

        StompSession session = connectAs(spectatorUser);
        CompletableFuture<String> broadcastFuture = new CompletableFuture<>();
        CompletableFuture<String> errorFuture = new CompletableFuture<>();
        StompHeaders subHeaders = new StompHeaders();
        subHeaders.setDestination(wsTopic);
        subHeaders.set("access-token", spectatorAccessToken);
        session.subscribe(subHeaders, stringHandler(broadcastFuture));
        session.subscribe("/user/queue/errors", stringHandler(errorFuture));
        Thread.sleep(150);

        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination(BingoRoomDestinations.APP_ROOM_PREFIX + roomId + "/mark");
        sendHeaders.set("access-token", spectatorAccessToken);
        session.send(sendHeaders, Map.of("cellIndex", 0, "marked", true));

        String errorFrame = errorFuture.get(5, TimeUnit.SECONDS);
        assertThat(errorFrame).contains("\"code\":\"SPECTATOR_CANNOT_MARK\"");
        assertThat(broadcastFuture).isNotCompleted();
    }

    private AuthFixture seedAuth() throws Exception {
        return PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private StompSession connectAs(final AuthFixture user) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", user.authorizationHeader());
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        StompSession session = client
                .connectAsync("ws://localhost:" + port + "/api/collaboratif/ws/collaboratif",
                        new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
                        })
                .get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
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
                    future.complete(new String((byte[]) payload, StandardCharsets.UTF_8));
                }
            }
        };
    }
}
