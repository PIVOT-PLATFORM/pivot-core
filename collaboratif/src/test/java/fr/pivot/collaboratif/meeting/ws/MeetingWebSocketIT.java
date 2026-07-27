package fr.pivot.collaboratif.meeting.ws;

import com.jayway.jsonpath.JsonPath;
import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.meeting.dto.MeetingStartedEvent;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for MeetOps meeting animation WebSocket room isolation (US12.2.1 AC-S3) —
 * the AC's own "mandatory" test, proving both that a legitimate subscriber receives broadcasts
 * (positive control, so the negative assertion below is not vacuously true) and that a
 * subscriber from a different tenant never does, even knowing the meeting id. Mirrors {@code
 * fr.pivot.collaboratif.whiteboard.ws.WhiteboardWebSocketIT}'s exact pattern — see that class's
 * own TSDoc for the shared connection/auth mechanics ({@link MeetingChannelInterceptor}, not
 * {@code WhiteboardChannelInterceptor}, does the authorization here, but both mount on the same
 * single {@code /collaboratif/ws/whiteboard} STOMP endpoint, see {@code
 * CollaboratifWebSocketConfig}).
 *
 * <p>Unlike whiteboard's channel, MeetOps animation is broadcast-only (no client {@code SEND}, see
 * {@link MeetingDestinations}'s own Javadoc) — the broadcast under test here is triggered via the
 * real {@code POST .../start} REST call (AC-01), issued through {@link MockMvc} (this module's
 * standard REST test client, see {@code fr.pivot.collaboratif.meeting.MeetingAnimationControllerIT})
 * rather than a STOMP {@code SEND} frame.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeetingWebSocketIT extends AbstractCollaboratifIntegrationTest {

    private static final String MEETINGS_PATH = "/collaboratif/meetings";

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;

    /** Keeps track of open sessions so they can be disconnected in teardown. */
    private final List<StompSession> openSessions = new ArrayList<>();

    /** Builds {@link #mockMvc} against the full Spring context, mirroring the REST ITs. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    /** Tears down all open WebSocket sessions after each test to prevent resource leaks. */
    @AfterEach
    void disconnectAll() {
        for (StompSession session : openSessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        openSessions.clear();
    }

    /**
     * Given the meeting's own organizer subscribed to its topic,
     * when the organizer starts the meeting via {@code POST .../start},
     * then the organizer receives the {@code MEETING_STARTED} broadcast — the positive control
     * proving this channel does broadcast under normal use (so the cross-tenant test below is not
     * vacuously true).
     */
    @Test
    void organizer_subscribed_receives_meeting_started_broadcast() throws Exception {
        AuthFixture owner = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        String meetingId = createMeetingWithOneItem(owner);

        StompSession session = connectAs(owner.rawToken());
        CompletableFuture<MeetingStartedEvent> startedFuture = new CompletableFuture<>();
        session.subscribe("/topic/collaboratif/meeting/" + meetingId,
                frameHandler(MeetingStartedEvent.class, startedFuture));

        Thread.sleep(150);
        startMeeting(owner, meetingId);

        // Only the event's own arrival matters here (the positive control for the cross-tenant
        // test below) — not a full payload round-trip, which would additionally exercise this
        // test client's bare (no java.time module) Jackson ObjectMapper against MeetingLiveStateDto's
        // Instant field, an unrelated concern to AC-S3's room-isolation guarantee under test.
        MeetingStartedEvent event = startedFuture.get(5, TimeUnit.SECONDS);
        assertThat(event.type()).isEqualTo(MeetingStartedEvent.EVENT_TYPE);
    }

    /**
     * Given a meeting created in tenant T1,
     * when a user from a different tenant T2 subscribes to {@code /topic/collaboratif/meeting/{id}}
     * (knowing the meeting id) and the organizer then starts the meeting,
     * then the T2 subscriber never receives the {@code MEETING_STARTED} broadcast — the
     * SUBSCRIBE was silently denied by {@link MeetingChannelInterceptor} (AC-S3).
     */
    @Test
    void cross_tenant_subscribe_never_receives_broadcast() throws Exception {
        AuthFixture owner = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        String meetingId = createMeetingWithOneItem(owner);

        AuthFixture otherTenantUser = PlatformAuthTestSupport.seedActiveUserWithToken(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        StompSession crossTenantSession = connectAs(otherTenantUser.rawToken());
        CompletableFuture<MeetingStartedEvent> startedFuture = new CompletableFuture<>();
        crossTenantSession.subscribe("/topic/collaboratif/meeting/" + meetingId,
                frameHandler(MeetingStartedEvent.class, startedFuture));

        Thread.sleep(150);
        startMeeting(owner, meetingId);

        assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> startedFuture.get(2, TimeUnit.SECONDS));
    }

    /**
     * Given no {@code Authorization} header on the STOMP {@code CONNECT} frame,
     * when a connection is attempted,
     * then the server rejects the CONNECT.
     */
    @Test
    void connect_without_bearer_token_is_rejected() {
        WebSocketStompClient client = createClient();
        CompletableFuture<StompSession> future = client.connectAsync(
                wsUrl(), new WebSocketHttpHeaders(), new StompSessionHandlerAdapter() {
                });

        assertThatExceptionOfType(ExecutionException.class)
                .isThrownBy(() -> future.get(5, TimeUnit.SECONDS));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the WebSocket URL for the shared collaboratif STOMP endpoint.
     *
     * @return the WebSocket URL including the context-path
     */
    private String wsUrl() {
        return "ws://localhost:" + port + "/api/collaboratif/ws/whiteboard";
    }

    /**
     * Creates a configured {@link WebSocketStompClient} using a raw WebSocket transport.
     *
     * @return a ready-to-use STOMP client
     */
    private WebSocketStompClient createClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        return client;
    }

    /**
     * Connects to the WebSocket endpoint using the given bearer token on the STOMP {@code CONNECT}
     * frame's native {@code Authorization} header, blocking until established or timing out.
     *
     * @param rawToken the raw bearer token to send as {@code Authorization: Bearer <token>}
     * @return the established {@link StompSession}
     * @throws Exception if the connection fails or times out
     */
    private StompSession connectAs(final String rawToken) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + rawToken);
        StompSession session = createClient()
                .connectAsync(wsUrl(), new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(5, TimeUnit.SECONDS);
        openSessions.add(session);
        return session;
    }

    /**
     * Creates a meeting with a single agenda item via the real {@code POST .../meetings} endpoint.
     *
     * @param caller the authenticated organizer
     * @return the created meeting's id
     */
    private String createMeetingWithOneItem(final AuthFixture caller) throws Exception {
        MvcResult result = mockMvc.perform(post(MEETINGS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", caller.authorizationHeader())
                        .content("""
                                {"title":"Sprint Review","scheduledAt":"2026-08-01T10:00:00Z",
                                 "totalDurationMinutes":30,
                                 "agendaItems":[{"title":"Point A","durationMinutes":5,"type":"INFO"}]}"""))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /**
     * Starts the given meeting via the real {@code POST .../start} endpoint (AC-01) — the trigger
     * for the {@code MEETING_STARTED} broadcast under test.
     *
     * @param caller    the authenticated organizer
     * @param meetingId the meeting's id
     */
    private void startMeeting(final AuthFixture caller, final String meetingId) throws Exception {
        mockMvc.perform(post(MEETINGS_PATH + "/" + meetingId + "/start")
                        .header("Authorization", caller.authorizationHeader()))
                .andExpect(status().isOk());
    }

    /**
     * Returns a {@link StompFrameHandler} that completes the given future with the received
     * payload.
     *
     * @param type   the expected payload class
     * @param future the future to complete
     * @param <T>    the payload type
     * @return a frame handler
     */
    private <T> StompFrameHandler frameHandler(final Class<T> type, final CompletableFuture<T> future) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(final StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(final StompHeaders headers, final Object payload) {
                if (!future.isDone()) {
                    future.complete(type.cast(payload));
                }
            }
        };
    }
}
