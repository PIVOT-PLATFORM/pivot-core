package fr.pivot.collaboratif.bingo.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BingoChannelInterceptor} (US47.1.1, SEC-01).
 */
@ExtendWith(MockitoExtension.class)
class BingoChannelInterceptorTest {

    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final String ACCESS_TOKEN = "valid-token";

    @Mock
    private BingoRoomAccessGrantService grantService;

    private BingoChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new BingoChannelInterceptor(grantService);
    }

    @Test
    void subscribe_toNonBingoDestination_passesThroughWithoutCheckingAnyGrant() {
        Message<byte[]> frame = frame(StompCommand.SUBSCRIBE, "/topic/whiteboard/" + ROOM_ID, null);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isSameAs(frame);
    }

    @Test
    void subscribe_withValidGrant_isAllowed() {
        when(grantService.hasAccess(ROOM_ID, ACCESS_TOKEN)).thenReturn(true);
        Message<byte[]> frame = frame(
                StompCommand.SUBSCRIBE, BingoRoomDestinations.roomTopic(ROOM_ID), ACCESS_TOKEN);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isSameAs(frame);
    }

    @Test
    void subscribe_withoutAnyGrant_isDenied() {
        when(grantService.hasAccess(ROOM_ID, null)).thenReturn(false);
        Message<byte[]> frame = frame(StompCommand.SUBSCRIBE, BingoRoomDestinations.roomTopic(ROOM_ID), null);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void subscribe_withInvalidGrant_isDenied() {
        when(grantService.hasAccess(ROOM_ID, "wrong-token")).thenReturn(false);
        Message<byte[]> frame = frame(
                StompCommand.SUBSCRIBE, BingoRoomDestinations.roomTopic(ROOM_ID), "wrong-token");

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void send_toMarkDestination_withValidGrant_isAllowed() {
        when(grantService.hasAccess(ROOM_ID, ACCESS_TOKEN)).thenReturn(true);
        Message<byte[]> frame = frame(
                StompCommand.SEND, BingoRoomDestinations.APP_ROOM_PREFIX + ROOM_ID + "/mark", ACCESS_TOKEN);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isSameAs(frame);
    }

    @Test
    void send_toMarkDestination_withoutGrant_isDenied() {
        when(grantService.hasAccess(ROOM_ID, null)).thenReturn(false);
        Message<byte[]> frame = frame(
                StompCommand.SEND, BingoRoomDestinations.APP_ROOM_PREFIX + ROOM_ID + "/mark", null);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void subscribe_toUnparseableRoomId_isDenied() {
        Message<byte[]> frame = frame(
                StompCommand.SUBSCRIBE, BingoRoomDestinations.TOPIC_ROOM_PREFIX + "not-a-uuid", ACCESS_TOKEN);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    private Message<byte[]> frame(final StompCommand command, final String destination, final String accessToken) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setSessionId("session-1");
        if (accessToken != null) {
            accessor.setNativeHeader(BingoChannelInterceptor.ACCESS_TOKEN_HEADER, accessToken);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
