package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.exception.SessionGuestExpiredException;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.collaboratif.session.dto.GuestHeartbeatRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionResponse;
import fr.pivot.collaboratif.session.dto.ParticipantJoinedEvent;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for the unified join endpoint and guest heartbeat (US19.2.1) — a single {@code
 * POST /sessions/join} serves both authenticated and anonymous {@code ROLE_GUEST} callers.
 */
@Service
public class SessionParticipantService {

    /**
     * Rolling TTL for a guest's liveness — a guest whose heartbeat is older than this is
     * considered expired ({@code GUEST_SESSION_EXPIRED}). Mirrors the whiteboard channel's own
     * 5-minute heartbeat cache TTL ({@code MembershipCacheService}).
     */
    private static final Duration GUEST_TTL = Duration.ofMinutes(5);

    private static final int GUEST_TOKEN_BYTES = 32;

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates the service with its required dependencies.
     *
     * @param sessionRepository     repository for join-code resolution
     * @param participantRepository repository for participant persistence
     * @param messagingTemplate     STOMP broadcaster for the {@code PARTICIPANT_JOINED} event
     */
    public SessionParticipantService(
            final SessionRepository sessionRepository,
            final ParticipantRepository participantRepository,
            final SimpMessagingTemplate messagingTemplate) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Joins a session by its code (US19.2.1) — authenticated when {@code principal} is present,
     * anonymous {@code ROLE_GUEST} otherwise. An unknown or already-completed session's code is
     * treated identically (404, anti-enumeration).
     *
     * @param request   the join request (code, display name)
     * @param principal the caller's resolved identity, or empty for an anonymous caller
     * @return the created participant's id, guest token (anonymous only), and WS topic
     * @throws SessionNotFoundException if the code does not resolve to a joinable session, or an
     *                                   authenticated caller's tenant does not own the session
     */
    @Transactional
    public JoinSessionResponse join(final JoinSessionRequest request, final Optional<AuthenticatedPrincipal> principal) {
        Session session = sessionRepository
                .findFirstByJoinCodeAndStatusNot(request.code(), SessionStatus.COMPLETED)
                .orElseThrow(SessionNotFoundException::new);
        if (principal.isPresent() && !session.getTenantId().equals(principal.get().tenantId())) {
            throw new SessionNotFoundException();
        }

        Instant now = Instant.now();
        Long userId = principal.map(AuthenticatedPrincipal::userId).orElse(null);
        String guestToken = principal.isPresent() ? null : generateGuestToken();
        Participant participant = participantRepository.save(
                new Participant(session.getId(), userId, guestToken, request.displayName(), now));

        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(session.getId()),
                new ParticipantJoinedEvent(participant.getId(), participant.getDisplayName()));

        return new JoinSessionResponse(
                participant.getId(), guestToken, SessionDestinations.topicFor(session.getId()));
    }

    /**
     * Refreshes a guest participant's rolling TTL (US19.2.1, guest only).
     *
     * @param sessionId     the session's UUID
     * @param participantId the participant's UUID
     * @param request       the heartbeat request carrying the guest token
     * @throws SessionGuestExpiredException if the participant is unknown, not a guest, presents
     *                                       the wrong token, or has exceeded the rolling TTL
     */
    @Transactional
    public void heartbeat(final UUID sessionId, final UUID participantId, final GuestHeartbeatRequest request) {
        Participant participant = participantRepository.findByIdAndSessionId(participantId, sessionId)
                .orElseThrow(SessionGuestExpiredException::new);
        if (!participant.isGuest() || !participant.getGuestToken().equals(request.token())) {
            throw new SessionGuestExpiredException();
        }
        Instant now = Instant.now();
        Instant lastHeartbeat = participant.getLastHeartbeatAt();
        if (lastHeartbeat == null || Duration.between(lastHeartbeat, now).compareTo(GUEST_TTL) > 0) {
            throw new SessionGuestExpiredException();
        }
        participant.refreshHeartbeat(now);
        participantRepository.save(participant);
    }

    private String generateGuestToken() {
        byte[] bytes = new byte[GUEST_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
