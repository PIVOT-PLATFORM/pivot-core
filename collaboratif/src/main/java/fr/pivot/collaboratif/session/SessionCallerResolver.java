package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.exception.SessionGuestExpiredException;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the acting {@link Participant} for a session activity write endpoint (POLL vote,
 * WORDCLOUD submission, ...) from either an {@code Authorization} bearer token or an {@code
 * X-Guest-Token} header — the same dual-credential shape as the unified join endpoint (US19.2.1)
 * and the STOMP CONNECT guest fallback, applied here to plain REST calls.
 */
@Component
public class SessionCallerResolver {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String GUEST_TOKEN_HEADER = "X-Guest-Token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthenticatedPrincipalResolver principalResolver;
    private final ParticipantRepository participantRepository;

    /**
     * Creates the resolver with its required dependencies.
     *
     * @param principalResolver     validates a raw bearer token
     * @param participantRepository resolves the participant row for either credential type
     */
    public SessionCallerResolver(
            final AuthenticatedPrincipalResolver principalResolver, final ParticipantRepository participantRepository) {
        this.principalResolver = principalResolver;
        this.participantRepository = participantRepository;
    }

    /**
     * Resolves the calling participant within a session.
     *
     * @param request   the current HTTP request
     * @param sessionId the session the caller must have joined
     * @return the participant id
     * @throws SessionNotFoundException     if an authenticated caller never joined this session
     * @throws SessionGuestExpiredException if neither a valid bearer token nor a valid, correctly
     *                                       scoped guest token is present
     */
    public UUID resolveParticipantId(final HttpServletRequest request, final UUID sessionId) {
        String rawToken = extractBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        if (rawToken != null) {
            AuthenticatedPrincipal principal =
                    principalResolver.resolve(rawToken).orElseThrow(SessionGuestExpiredException::new);
            return participantRepository.findBySessionIdAndUserId(sessionId, principal.userId())
                    .orElseThrow(SessionNotFoundException::new)
                    .getId();
        }

        String guestToken = request.getHeader(GUEST_TOKEN_HEADER);
        if (guestToken == null || guestToken.isBlank()) {
            throw new SessionGuestExpiredException();
        }
        Participant participant = participantRepository.findByGuestToken(guestToken)
                .orElseThrow(SessionGuestExpiredException::new);
        if (!participant.getSessionId().equals(sessionId)) {
            throw new SessionGuestExpiredException();
        }
        return participant.getId();
    }

    private static String extractBearerToken(final String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        final String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Resolves the caller's {@link AuthenticatedPrincipal}, if the request carries a valid bearer
     * token — used by the unified join endpoint, which must distinguish "no credential" (anonymous
     * join) from "credential present" without forcing a 401 the way {@code
     * CollaboratifRequestPrincipalResolver} does.
     *
     * @param request the current HTTP request
     * @return the resolved principal, or empty if no valid bearer token is present
     */
    public Optional<AuthenticatedPrincipal> resolveOptionalPrincipal(final HttpServletRequest request) {
        String rawToken = extractBearerToken(request.getHeader(AUTHORIZATION_HEADER));
        return rawToken == null ? Optional.empty() : principalResolver.resolve(rawToken);
    }
}
