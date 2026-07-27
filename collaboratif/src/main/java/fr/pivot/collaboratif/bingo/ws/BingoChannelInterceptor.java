package fr.pivot.collaboratif.bingo.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP channel interceptor that enforces Bingo room isolation on every inbound frame (US47.1.1,
 * SEC-01) — declines {@code fr.pivot.agilite.poker.ws.PokerChannelInterceptor}'s grant-check
 * pattern inside this module (no inter-module dependency is possible, ADR-006), without the
 * poker interceptor's rate-limiting (out of this US's AC scope).
 *
 * <p>Acts only on destinations under {@link BingoRoomDestinations#TOPIC_ROOM_PREFIX}/{@link
 * BingoRoomDestinations#APP_ROOM_PREFIX} — any other destination on this module's shared client
 * inbound channel passes through unchanged:
 * <ul>
 *   <li><b>SUBSCRIBE</b> — verifies the caller presents a currently valid room access grant
 *       (native header {@value #ACCESS_TOKEN_HEADER}, checked via
 *       {@link BingoRoomAccessGrantService#hasAccess}) before allowing a subscription to
 *       {@code /topic/collaboratif/bingo/{roomId}}. Denied requests are dropped (the subscription
 *       is never established); the WebSocket session itself is not closed.</li>
 *   <li><b>SEND</b> — verifies the same grant for any message destined at
 *       {@code /app/collaboratif/bingo/{roomId}/mark}.</li>
 * </ul>
 *
 * <p><strong>No principal is ever read here</strong> — authorization is governed exclusively by
 * the {@code (roomId, accessToken)} grant pair (SEC-02), independent of whatever identity (bearer
 * principal, guest principal, or none) the CONNECT frame established.
 */
@Component
public class BingoChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(BingoChannelInterceptor.class);

    /** Native STOMP header carrying the room access grant token. */
    public static final String ACCESS_TOKEN_HEADER = "access-token";

    private final BingoRoomAccessGrantService roomAccessGrantService;

    /**
     * Messaging template used to deliver error notifications to denied sessions. Lazily injected
     * to avoid a circular dependency during broker configuration.
     */
    @Lazy
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the interceptor with the required dependency.
     *
     * @param roomAccessGrantService grant store used for SUBSCRIBE/SEND authorization decisions
     */
    public BingoChannelInterceptor(final BingoRoomAccessGrantService roomAccessGrantService) {
        this.roomAccessGrantService = roomAccessGrantService;
    }

    /**
     * Intercepts inbound STOMP frames, enforcing room access grants on Bingo destinations.
     *
     * @param message the inbound STOMP message
     * @param channel the inbound channel
     * @return the message if the frame is allowed, {@code null} to silently drop it
     */
    @Override
    public Message<?> preSend(final Message<?> message, final MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        return switch (command) {
            case SUBSCRIBE -> authorize(message, accessor, BingoRoomDestinations.TOPIC_ROOM_PREFIX);
            case SEND -> authorize(message, accessor, BingoRoomDestinations.APP_ROOM_PREFIX);
            default -> message;
        };
    }

    private Message<?> authorize(final Message<?> message, final StompHeaderAccessor accessor, final String prefix) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(prefix)) {
            return message;
        }
        String roomIdStr = BingoRoomDestinations.extractRoomId(destination, prefix);
        UUID roomId = parseUuid(roomIdStr);
        if (roomId == null) {
            LOG.warn("Bingo frame denied: unparseable roomId in destination={}", destination);
            return null;
        }
        String accessToken = accessor.getFirstNativeHeader(ACCESS_TOKEN_HEADER);
        if (!roomAccessGrantService.hasAccess(roomId, accessToken)) {
            LOG.warn("Bingo frame denied: no valid access grant for room={}", roomId);
            sendError(accessor.getUser(), "Access denied to room " + roomId);
            return null;
        }
        return message;
    }

    private void sendError(final Principal user, final String error) {
        if (user == null || messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(user.getName(), "/queue/errors", new BingoErrorPayload(error));
        } catch (Exception e) {
            LOG.debug("Could not deliver Bingo error notification: {}", e.getMessage());
        }
    }

    private UUID parseUuid(final String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
