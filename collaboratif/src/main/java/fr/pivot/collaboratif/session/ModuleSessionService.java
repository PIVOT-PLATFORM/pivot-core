package fr.pivot.collaboratif.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.InvalidSessionTransitionException;
import fr.pivot.collaboratif.session.dto.CreateSessionRequest;
import fr.pivot.collaboratif.session.dto.SessionLifecycleEvent;
import fr.pivot.collaboratif.session.dto.SessionResponse;
import fr.pivot.collaboratif.session.dto.SessionStartedEvent;
import fr.pivot.collaboratif.session.poll.PollActivityService;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for session creation, listing, and lifecycle transitions (US19.1.1, US19.1.2).
 *
 * <p><strong>Named {@code ModuleSessionService}, not the bare {@code SessionService}</strong> —
 * the shell already owns {@code fr.pivot.auth.service.SessionService} (login/device session
 * management); see {@link ModuleSessionController}'s Javadoc for the full collision rationale.
 */
@Service
public class ModuleSessionService {

    private final SessionRepository sessionRepository;
    private final ActivityRepository activityRepository;
    private final ParticipantRepository participantRepository;
    private final SessionAccessService accessService;
    private final SessionJoinCodeGenerator joinCodeGenerator;
    private final PollActivityService pollActivityService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service with its required dependencies.
     *
     * @param sessionRepository     repository for sessions
     * @param activityRepository    repository for the 1:1 activity marker row
     * @param participantRepository repository used to compute participant counts
     * @param accessService         resolves sessions with tenant/authorization enforcement
     * @param joinCodeGenerator     generates unique join codes
     * @param pollActivityService   materializes POLL options at creation time
     * @param messagingTemplate     STOMP broadcaster for lifecycle events
     * @param objectMapper          JSON (de)serializer for {@code config}
     */
    public ModuleSessionService(
            final SessionRepository sessionRepository,
            final ActivityRepository activityRepository,
            final ParticipantRepository participantRepository,
            final SessionAccessService accessService,
            final SessionJoinCodeGenerator joinCodeGenerator,
            final PollActivityService pollActivityService,
            final SimpMessagingTemplate messagingTemplate,
            final ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.activityRepository = activityRepository;
        this.participantRepository = participantRepository;
        this.accessService = accessService;
        this.joinCodeGenerator = joinCodeGenerator;
        this.pollActivityService = pollActivityService;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new session in {@link SessionStatus#DRAFT} (US19.1.1).
     *
     * @param request   the creation request
     * @param principal the caller, becomes {@code createdBy}
     * @return the created session
     */
    @Transactional
    public SessionResponse create(final CreateSessionRequest request, final CollaboratifRequestPrincipal principal) {
        String joinCode = joinCodeGenerator.generate(principal.tenantId());
        String config = writeConfig(request.config());
        Instant now = Instant.now();
        Session session = sessionRepository.save(new Session(
                principal.tenantId(), request.teamId(), request.title(), request.type(),
                joinCode, config, principal.userId(), now));
        activityRepository.save(new Activity(session.getId(), request.type()));
        if (pollActivityService.supports(request.type())) {
            pollActivityService.initializeFromConfig(session.getId(), request.config());
        }
        return toResponse(session);
    }

    /**
     * Lists sessions visible to the caller (US19.1.1) — creator's own sessions plus sessions of
     * any team the caller belongs to, optionally filtered by {@code teamId}/{@code status}.
     *
     * @param principal the caller
     * @param teamId    optional team filter
     * @param status    optional status filter
     * @return the visible sessions, most recently created first
     */
    @Transactional(readOnly = true)
    public List<SessionResponse> list(
            final CollaboratifRequestPrincipal principal, final Long teamId, final SessionStatus status) {
        return sessionRepository.findAllByTenantIdOrderByCreatedAtDesc(principal.tenantId()).stream()
                .filter(session -> accessService.isVisibleToCaller(session, principal))
                .filter(session -> teamId == null || teamId.equals(session.getTeamId()))
                .filter(session -> status == null || status == session.getStatus())
                .map(this::toResponse)
                .toList();
    }

    /**
     * Retrieves a single session for the caller (US19.1.1).
     *
     * @param sessionId the session's UUID
     * @param principal the caller
     * @return the session
     */
    @Transactional(readOnly = true)
    public SessionResponse getById(final UUID sessionId, final CollaboratifRequestPrincipal principal) {
        return toResponse(accessService.resolveSessionForCaller(sessionId, principal));
    }

    /**
     * Starts a DRAFT session (US19.1.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param sessionId the session's UUID
     * @param principal the caller
     */
    @Transactional
    public void start(final UUID sessionId, final CollaboratifRequestPrincipal principal) {
        Session session = accessService.resolveSessionForOwnerOrAdmin(sessionId, principal);
        requireTransition(session, SessionStatus.DRAFT);
        Instant now = Instant.now();
        session.setStatus(SessionStatus.LIVE);
        session.setStartedAt(now);
        sessionRepository.save(session);
        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(sessionId), new SessionStartedEvent(toResponse(session)));
    }

    /**
     * Pauses a LIVE session (US19.1.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param sessionId the session's UUID
     * @param principal the caller
     */
    @Transactional
    public void pause(final UUID sessionId, final CollaboratifRequestPrincipal principal) {
        Session session = accessService.resolveSessionForOwnerOrAdmin(sessionId, principal);
        requireTransition(session, SessionStatus.LIVE);
        session.setStatus(SessionStatus.PAUSED);
        sessionRepository.save(session);
        broadcastLifecycle(sessionId, SessionLifecycleEvent.PAUSED);
    }

    /**
     * Resumes a PAUSED session (US19.1.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param sessionId the session's UUID
     * @param principal the caller
     */
    @Transactional
    public void resume(final UUID sessionId, final CollaboratifRequestPrincipal principal) {
        Session session = accessService.resolveSessionForOwnerOrAdmin(sessionId, principal);
        requireTransition(session, SessionStatus.PAUSED);
        session.setStatus(SessionStatus.LIVE);
        sessionRepository.save(session);
        broadcastLifecycle(sessionId, SessionLifecycleEvent.RESUMED);
    }

    /**
     * Ends a LIVE or PAUSED session (US19.1.2) — owner or {@code ROLE_ADMIN} only.
     *
     * @param sessionId the session's UUID
     * @param principal the caller
     */
    @Transactional
    public void end(final UUID sessionId, final CollaboratifRequestPrincipal principal) {
        Session session = accessService.resolveSessionForOwnerOrAdmin(sessionId, principal);
        if (session.getStatus() != SessionStatus.LIVE && session.getStatus() != SessionStatus.PAUSED) {
            throw new InvalidSessionTransitionException(
                    "Cannot end a session in status " + session.getStatus());
        }
        session.setStatus(SessionStatus.COMPLETED);
        session.setEndedAt(Instant.now());
        sessionRepository.save(session);
        broadcastLifecycle(sessionId, SessionLifecycleEvent.ENDED);
    }

    private void requireTransition(final Session session, final SessionStatus expectedCurrent) {
        if (session.getStatus() != expectedCurrent) {
            throw new InvalidSessionTransitionException(
                    "Cannot transition session from status " + session.getStatus());
        }
    }

    private void broadcastLifecycle(final UUID sessionId, final String eventType) {
        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(sessionId), new SessionLifecycleEvent(eventType, sessionId));
    }

    private SessionResponse toResponse(final Session session) {
        long participantCount = participantRepository.countBySessionId(session.getId());
        return new SessionResponse(
                session.getId(), session.getTitle(), session.getType(), session.getStatus(),
                session.getJoinCode(), readConfig(session.getConfig()), session.getTeamId(),
                participantCount, session.getCreatedAt(), session.getStartedAt(), session.getEndedAt());
    }

    private String writeConfig(final JsonNode config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize session config", e);
        }
    }

    private JsonNode readConfig(final String config) {
        try {
            return objectMapper.readTree(config);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to deserialize session config", e);
        }
    }
}
