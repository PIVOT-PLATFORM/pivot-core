package fr.pivot.collaboratif.meeting.ws;

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
 * STOMP channel interceptor that authorizes SUBSCRIBE frames on MeetOps meeting animation topics
 * (US12.2.1 AC-S3), mirroring {@code fr.pivot.collaboratif.session.ws.SessionChannelInterceptor}'s
 * membership-check pattern for the {@code meeting} destination family.
 *
 * <p>Only SUBSCRIBE is authorized here: every meeting animation write ({@code start}, {@code
 * agenda/next}, {@code end}, {@code actions}) is a REST call (AC-01/AC-03/AC-06/AC-08), so this
 * channel is broadcast-only ({@code MEETING_STARTED}, {@code TIMER_TICK}, {@code
 * AGENDA_ITEM_CHANGED}, {@code MEETING_ENDED}, {@code MEETING_ACTION_ADDED}) — there is no SEND
 * app-destination to authorize.
 *
 * <p>Meeting participants are always authenticated {@link StompPrincipal}s — unlike Module
 * Sessions, MeetOps has no guest-token concept, so no second principal shape needs handling here.
 * A denied frame is silently dropped and the caller is notified on {@code /user/queue/errors},
 * exactly mirroring {@code SessionChannelInterceptor}'s behavior (no fuite d'événement
 * cross-room/cross-tenant, AC-S3).
 */
@Component
public class MeetingChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(MeetingChannelInterceptor.class);

    private final MeetingMembershipCacheService membershipCacheService;

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
    public MeetingChannelInterceptor(final MeetingMembershipCacheService membershipCacheService) {
        this.membershipCacheService = membershipCacheService;
    }

    /**
     * Intercepts inbound STOMP frames, authorizing SUBSCRIBE to meeting topics.
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
        UUID meetingId = MeetingDestinations.meetingIdFrom(destination);
        if (meetingId == null) {
            return message;
        }
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal stompPrincipal
                && membershipCacheService.isMember(stompPrincipal.tenantId(), meetingId, stompPrincipal.userId())) {
            return message;
        }
        LOG.warn("SUBSCRIBE denied: principal not authorized for meeting={}", meetingId);
        sendError(user, "Access denied to meeting " + meetingId);
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
