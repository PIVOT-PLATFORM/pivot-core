package fr.pivot.collaboratif.meetops.booking.ws;

import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingRepository;
import fr.pivot.collaboratif.meetops.booking.MeetingParticipantRepository;
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
import java.util.Optional;
import java.util.UUID;

/**
 * STOMP channel interceptor that authorizes SUBSCRIBE frames on MeetOps booking topics (US12.4.1
 * "Sécurité — autorisation room STOMP"), mirroring {@code
 * fr.pivot.collaboratif.session.ws.SessionChannelInterceptor}'s pattern.
 *
 * <p>Only SUBSCRIBE is authorized here — every booking-flow write (confirm, adjust) is a REST
 * call (US12.4.1's own controller ACs), the WS channel is broadcast-only.
 *
 * <p>Authorized principals: the meeting's organizer ({@code Meeting#getCreatedBy}) or a resolved
 * participant ({@code MeetingParticipantRepository#existsByMeetingIdAndParticipantUserId}) of the
 * caller's own tenant. A meeting belonging to another tenant, or one the caller is neither the
 * organizer of nor a resolved participant of, is denied with a STOMP ERROR frame — the other
 * subscribers of the broker are never disconnected (per-frame, per-session denial only, same
 * posture as {@code WhiteboardChannelInterceptor}/{@code SessionChannelInterceptor}).
 */
@Component
public class MeetingChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(MeetingChannelInterceptor.class);

    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;

    /**
     * Messaging template used to deliver error notifications to denied sessions. Lazily injected
     * to avoid a circular dependency during broker configuration.
     */
    @Lazy
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the interceptor with the required dependencies.
     *
     * @param meetingRepository             repository for tenant-isolation/organizer checks
     * @param meetingParticipantRepository  repository for participant-resolution checks
     */
    public MeetingChannelInterceptor(
            final MeetingRepository meetingRepository,
            final MeetingParticipantRepository meetingParticipantRepository) {
        this.meetingRepository = meetingRepository;
        this.meetingParticipantRepository = meetingParticipantRepository;
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
        UUID meetingId = MeetingDestinations.meetingIdFrom(accessor.getDestination());
        if (meetingId == null) {
            return message;
        }
        Principal user = accessor.getUser();
        if (user instanceof StompPrincipal stompPrincipal && isAuthorized(meetingId, stompPrincipal)) {
            return message;
        }
        LOG.warn("SUBSCRIBE denied: principal not authorized for meeting={}", meetingId);
        sendError(user, "Access denied to meeting " + meetingId);
        return null;
    }

    private boolean isAuthorized(final UUID meetingId, final StompPrincipal principal) {
        Optional<Meeting> meeting = meetingRepository.findByIdAndTenantId(meetingId, principal.tenantId());
        if (meeting.isEmpty()) {
            return false;
        }
        Long organizerId = meeting.get().getCreatedBy();
        if (organizerId != null && organizerId.equals(principal.userId())) {
            return true;
        }
        return meetingParticipantRepository.existsByMeetingIdAndParticipantUserId(meetingId, principal.userId());
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
