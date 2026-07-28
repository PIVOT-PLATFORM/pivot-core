package fr.pivot.collaboratif.meeting.ws;

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
import java.util.UUID;

/**
 * STOMP channel interceptor that authorizes SUBSCRIBE frames on the MeetOps meeting real-time
 * topic (US12.2.1 AC-S3, extended by US12.4.1), mirroring {@code
 * fr.pivot.collaboratif.session.ws.SessionChannelInterceptor}'s membership-check pattern for the
 * {@code meeting} destination family.
 *
 * <p>Only SUBSCRIBE is authorized here: every write on this topic (animation's {@code start}/
 * {@code agenda/next}/{@code end}/{@code actions}, US12.2.1; booking's {@code confirm}/{@code
 * adjust}, US12.4.1) is a REST call, so this channel is broadcast-only — there is no SEND
 * app-destination to authorize.
 *
 * <p>A caller is authorized if <strong>either</strong>: (1) {@link
 * MeetingMembershipCacheService#isMember} — the meeting's owner, or a member of its optional team
 * (US12.2.1's original animation-membership rule); <strong>or</strong> (2) the meeting's
 * best-effort-resolved organizer ({@code Meeting#getCreatedBy}) or a resolved booking-flow
 * participant ({@link MeetingParticipantRepository#existsByMeetingIdAndParticipantUserId},
 * US12.4.1) — a booking-flow meeting's participants are not necessarily members of its (possibly
 * absent) team, so rule (1) alone would wrongly deny them. Reconciled here rather than as two
 * competing interceptors on the same topic, which would both collide on Spring's default bean
 * name and leave only one authorization model actually enforced.
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
     * @param membershipCacheService        cache used to authorize animation-membership callers
     *                                       (owner or team member)
     * @param meetingRepository              repository used to resolve a booking-flow meeting's
     *                                       organizer (tenant-scoped)
     * @param meetingParticipantRepository   repository used to resolve booking-flow participants
     */
    public MeetingChannelInterceptor(
            final MeetingMembershipCacheService membershipCacheService,
            final MeetingRepository meetingRepository,
            final MeetingParticipantRepository meetingParticipantRepository) {
        this.membershipCacheService = membershipCacheService;
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
        String destination = accessor.getDestination();
        UUID meetingId = MeetingDestinations.meetingIdFrom(destination);
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
        if (membershipCacheService.isMember(principal.tenantId(), meetingId, principal.userId())) {
            return true;
        }
        return meetingRepository.findByIdAndTenantId(meetingId, principal.tenantId())
                .map(meeting -> {
                    Long organizerId = meeting.getCreatedBy();
                    if (organizerId != null && organizerId.equals(principal.userId())) {
                        return true;
                    }
                    return meetingParticipantRepository.existsByMeetingIdAndParticipantUserId(
                            meetingId, principal.userId());
                })
                .orElse(false);
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
