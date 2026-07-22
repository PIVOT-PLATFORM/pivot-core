package fr.pivot.agilite.standup.ws;

import fr.pivot.agilite.standup.StandupSessionService;
import fr.pivot.agilite.ws.WsErrorPayload;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
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
 * STOMP channel interceptor that enforces standup session subscription isolation on every
 * inbound frame (US10.1.2).
 *
 * <p><strong>Exact mirror of {@code fr.pivot.agilite.wheel.ws.WheelChannelInterceptor}</strong> —
 * same "only ever act on this domain's own destination prefix, deny by silently dropping the
 * frame plus a {@code /user/queue/errors} notification, never close the session" shape, and the
 * same authorization mechanism: a standup session is scoped to a permanent team (exactly like a
 * wheel), not an ad hoc session joined via an invite code/link like a planning-poker room or a
 * retrospective session — authorization is the caller's real platform identity (resolved from the
 * same bearer token the REST endpoints require) plus the same team-membership check {@link
 * StandupSessionService} already enforces for its own REST endpoints.
 *
 * <p>Only the {@code SUBSCRIBE} command is handled — like wheels, no client-to-server application
 * destination exists for standup sessions (every mutation is a REST call, never a STOMP {@code
 * SEND}), so there is nothing to rate-limit or otherwise authorize on the {@code SEND} path.
 *
 * <p>{@link SimpMessagingTemplate} is injected lazily to break the circular dependency that would
 * arise because this interceptor is registered on the client inbound channel during Spring's
 * message broker configuration phase, before the messaging template is fully initialised (same
 * rationale as {@code WheelChannelInterceptor}).
 */
@Component
public class StandupChannelInterceptor implements ChannelInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(StandupChannelInterceptor.class);

    /**
     * Native STOMP header carrying the caller's real platform bearer token — same header name
     * and {@code Bearer } prefix convention as the {@code Authorization} HTTP header consumed by
     * {@code RequestPrincipalResolver} for REST endpoints.
     */
    static final String AUTHORIZATION_HEADER = "Authorization";

    /** Case-insensitive prefix expected before the raw token value, mirroring the REST header. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Generic denial reason — never distinguishes the exact cause (see class JavaDoc). */
    private static final String ACCESS_DENIED_MESSAGE = "Access denied to standup session ";

    private final AuthenticatedPrincipalResolver principalResolver;
    private final StandupSessionService sessionService;

    /**
     * Messaging template used to deliver error notifications to denied sessions. Lazily injected
     * to avoid a circular dependency during broker configuration (see class JavaDoc).
     */
    @Lazy
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the interceptor with the required dependencies.
     *
     * @param principalResolver validates a raw bearer token, resolving the caller's real
     *                          {@code userId}/{@code tenantId} — the same bean {@code
     *                          RequestPrincipalResolver} uses for REST endpoints
     * @param sessionService    resolves whether the caller may access a given standup session,
     *                          reusing the exact same existence/tenant/team-membership check as
     *                          the REST endpoints
     */
    public StandupChannelInterceptor(
            final AuthenticatedPrincipalResolver principalResolver, final StandupSessionService sessionService) {
        this.principalResolver = principalResolver;
        this.sessionService = sessionService;
    }

    /**
     * Intercepts inbound STOMP frames, enforcing standup session subscription authorization.
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
        if (accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        return handleSubscribe(message, accessor);
    }

    /**
     * Authorises a SUBSCRIBE frame targeting a standup session topic.
     *
     * <p>Destinations that do not start with {@link StandupDestinations#TOPIC_STANDUP_PREFIX} are
     * passed through unchanged — this interceptor never acts on other domains' traffic.
     *
     * @param message  the SUBSCRIBE frame
     * @param accessor the STOMP header accessor
     * @return the original message if allowed, {@code null} if denied
     */
    private Message<?> handleSubscribe(final Message<?> message, final StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(StandupDestinations.TOPIC_STANDUP_PREFIX)) {
            return message;
        }
        String sessionIdStr = StandupDestinations.extractSessionId(destination);
        if (sessionIdStr == null) {
            LOG.warn("SUBSCRIBE denied: destination has no sessionId segment: {}", destination);
            return null;
        }
        UUID sessionId = parseUuid(sessionIdStr);
        if (sessionId == null) {
            LOG.warn("SUBSCRIBE denied: unparseable sessionId in destination={}", destination);
            return null;
        }

        String rawToken = extractBearerToken(accessor.getFirstNativeHeader(AUTHORIZATION_HEADER));
        Optional<AuthenticatedPrincipal> principal =
                rawToken == null ? Optional.empty() : principalResolver.resolve(rawToken);
        if (principal.isEmpty()) {
            LOG.warn("SUBSCRIBE denied: no valid bearer token for standup session={}", sessionId);
            sendError(accessor.getUser(), sessionId);
            return null;
        }

        boolean accessible = sessionService.isAccessibleTo(
                sessionId, principal.get().userId(), principal.get().tenantId());
        if (!accessible) {
            LOG.warn("SUBSCRIBE denied: caller not authorized for standup session={}", sessionId);
            sendError(accessor.getUser(), sessionId);
            return null;
        }
        return message;
    }

    /**
     * Extracts the raw token from an {@code Authorization} native header value, requiring a
     * case-insensitive {@code Bearer } prefix — mirrors {@code RequestPrincipalResolver}'s
     * identical REST-side parsing (small, deliberate duplication across the STOMP/HTTP boundary,
     * same convention already established by {@code PokerChannelInterceptor}/{@code
     * RetroChannelInterceptor}/{@code WheelChannelInterceptor}).
     *
     * @param authorizationHeader the raw native header value, may be {@code null}
     * @return the raw token, or {@code null} if the header is absent, malformed, or the prefix
     *     does not match
     */
    private static String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Sends a generic denial notification to the session's {@code /user/queue/errors}
     * destination — never distinguishes missing/invalid token from a valid-but-unauthorized
     * caller (anti-enumeration, same convention as the REST 404s of US10.1.1/US10.1.2).
     *
     * @param user      the session principal (for user-scoped routing), or {@code null}
     * @param sessionId the standup session the caller attempted to subscribe to
     */
    private void sendError(final Principal user, final UUID sessionId) {
        if (user == null || messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(
                    user.getName(), "/queue/errors", new WsErrorPayload(ACCESS_DENIED_MESSAGE + sessionId));
        } catch (Exception e) {
            LOG.debug("Could not deliver error notification for standup session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Parses a UUID string, returning {@code null} on any parse failure.
     *
     * @param value the raw UUID string
     * @return the parsed {@link UUID} or {@code null}
     */
    private UUID parseUuid(final String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
