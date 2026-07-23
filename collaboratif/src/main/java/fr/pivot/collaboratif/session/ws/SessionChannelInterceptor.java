package fr.pivot.collaboratif.session.ws;

import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
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
 * STOMP channel interceptor that authorizes SUBSCRIBE frames on Module Session topics (US19.1.2
 * EN19.2), mirroring {@link fr.pivot.collaboratif.whiteboard.ws.WhiteboardChannelInterceptor}'s
 * membership-check pattern.
 *
 * <p>Only SUBSCRIBE is authorized here: unlike the whiteboard, every session write (vote, word
 * submission, lifecycle transition, join) is a REST call per US19.*'s ACs — the WS channel is
 * broadcast-only (SESSION_STARTED, PARTICIPANT_JOINED, POLL_UPDATED, WORD_ADDED, ...), so there
 * is no SEND app-destination to authorize and, for the same reason, no rate-limiting is applied
 * (unlike {@code WhiteboardChannelInterceptor}, which does police client-originated SEND traffic).
 *
 * <p>Two principal shapes are accepted, matching {@link StompAuthenticationChannelInterceptor}'s
 * bearer/guest-token CONNECT paths: an authenticated {@link StompPrincipal} (checked via {@link
 * SessionMembershipCacheService}) or a {@link SessionGuestPrincipal} (self-scoped by
 * construction — a guest token only ever resolves to its own session, so the only check needed
 * is that the subscribed destination matches that session).
 */
@Component
public class SessionChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(SessionChannelInterceptor.class);

    private final SessionMembershipCacheService membershipCacheService;

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
     * @param membershipCacheService cache used to authorize authenticated participants
     */
    public SessionChannelInterceptor(final SessionMembershipCacheService membershipCacheService) {
        this.membershipCacheService = membershipCacheService;
    }

    /**
     * Intercepts inbound STOMP frames, authorizing SUBSCRIBE to session topics.
     *
     * @param message the inbound STOMP message
     * @param channel the inbound channel
     * @return the message if the frame is allowed, {@code null} to silently drop it
     */
    @Override
    public Message<?> preSend(final Message<?> message, final MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        String destination = accessor.getDestination();
        UUID sessionId = SessionDestinations.sessionIdFrom(destination);
        if (sessionId == null) {
            return message;
        }
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal stompPrincipal) {
            if (membershipCacheService.isMember(stompPrincipal.tenantId(), sessionId, stompPrincipal.userId())) {
                return message;
            }
        } else if (user instanceof SessionGuestPrincipal guestPrincipal) {
            if (guestPrincipal.sessionId().equals(sessionId)) {
                return message;
            }
        }
        LOG.warn("SUBSCRIBE denied: principal not authorized for session={}", sessionId);
        sendError(user, "Access denied to session " + sessionId);
        return null;
    }

    private void sendError(final Principal user, final String error) {
        if (user == null || messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(user.getName(), "/queue/errors", new ErrorPayload(error));
        } catch (Exception e) {
            LOG.debug("Could not deliver error notification: {}", e.getMessage());
        }
    }

    /**
     * Error payload sent to {@code /user/queue/errors} on a denied SUBSCRIBE.
     *
     * @param error human-readable rejection reason
     */
    private record ErrorPayload(String error) {
    }
}
