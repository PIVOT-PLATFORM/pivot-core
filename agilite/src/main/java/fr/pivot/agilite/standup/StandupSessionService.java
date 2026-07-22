package fr.pivot.agilite.standup;

import fr.pivot.agilite.auth.entity.PlatformTeam;
import fr.pivot.agilite.auth.entity.PlatformTeamMember;
import fr.pivot.agilite.auth.entity.PlatformUser;
import fr.pivot.agilite.auth.repository.PlatformTeamMemberReadRepository;
import fr.pivot.agilite.auth.repository.PlatformUserReadRepository;
import fr.pivot.agilite.exception.StandupConflictException;
import fr.pivot.agilite.exception.StandupSessionNotFoundException;
import fr.pivot.agilite.exception.StandupValidationException;
import fr.pivot.agilite.standup.dto.ParticipantChangedEvent;
import fr.pivot.agilite.standup.dto.ParticipantSkippedEvent;
import fr.pivot.agilite.standup.dto.ParticipantsReorderedEvent;
import fr.pivot.agilite.standup.dto.SessionEndedEvent;
import fr.pivot.agilite.standup.dto.SessionStartedEvent;
import fr.pivot.agilite.standup.dto.StandupParticipantResponse;
import fr.pivot.agilite.standup.dto.StandupSessionResponse;
import fr.pivot.agilite.standup.dto.TimerExtendedEvent;
import fr.pivot.agilite.standup.ws.StandupDestinations;
import fr.pivot.agilite.team.TeamMembershipService;
import fr.pivot.agilite.team.dto.TeamResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for daily standup session lifecycle (US10.1.1/US10.1.2) and animation control
 * (US10.2.2).
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #create}/{@link #getById}/{@link #list}/{@link #delete} — CRUD (US10.1.1).</li>
 *   <li>{@link #start} — {@code PENDING} → {@code RUNNING}, first participant {@code SPEAKING}
 *       (US10.1.2).</li>
 *   <li>{@link #next}/{@link #skip} — facilitator-triggered rotation, sharing {@link
 *       #advanceRotation} with the sole difference of the outgoing participant's terminal status
 *       (US10.1.2/US10.2.2).</li>
 *   <li>{@link #autoAdvance} — the same rotation, triggered by {@link StandupTimerScheduler} once
 *       the current speaker's time has expired (US10.2.1). No caller identity to check — this is
 *       a system action, mirroring {@code RetroPhaseService#autoTransitionToRevue}.</li>
 *   <li>{@link #end} — facilitator-triggered early end, before rotation reaches the last
 *       participant (US10.1.2).</li>
 *   <li>{@link #extend}/{@link #reorder} — independent, combinable animation controls
 *       (US10.2.2).</li>
 * </ul>
 */
@Service
public class StandupSessionService {

    private static final Logger LOG = LoggerFactory.getLogger(StandupSessionService.class);

    /** Accepted values for {@code POST .../extend}'s {@code seconds} field (US10.2.2 AC). */
    private static final Set<Integer> ALLOWED_EXTEND_SECONDS = Set.of(30, 60);

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MIN_TIME_PER_PERSON_SECONDS = 30;
    private static final int MAX_TIME_PER_PERSON_SECONDS = 1800;

    private final StandupSessionRepository sessionRepository;
    private final StandupParticipantRepository participantRepository;
    private final TeamMembershipService teamMembershipService;
    private final PlatformTeamMemberReadRepository teamMemberRepository;
    private final PlatformUserReadRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    /**
     * Constructs the service with all required dependencies.
     *
     * @param sessionRepository     repository for session persistence
     * @param participantRepository repository for participant persistence, including the atomic
     *                              rotation guard ({@link
     *                              StandupParticipantRepository#finishIfSpeaking})
     * @param teamMembershipService shared team-resolution/membership-check + display-name helper
     * @param teamMemberRepository  read-only access to {@code public.team_members}, for
     *                              validating a participant reference
     * @param userRepository        read-only access to {@code public.users}, for resolving a
     *                              participant's display label
     * @param messagingTemplate     used to broadcast every lifecycle/animation event — injected
     *                              lazily to break the circular dependency that would otherwise
     *                              arise via {@link fr.pivot.agilite.standup.ws.StandupChannelInterceptor}
     *                              (registered on the client inbound channel during Spring's
     *                              message broker configuration phase, before the messaging
     *                              template is fully initialised — same rationale as {@code
     *                              WheelChannelInterceptor}'s own lazily-injected field)
     * @param clock                 the shared clock, overridable in tests
     */
    public StandupSessionService(
            final StandupSessionRepository sessionRepository,
            final StandupParticipantRepository participantRepository,
            final TeamMembershipService teamMembershipService,
            final PlatformTeamMemberReadRepository teamMemberRepository,
            final PlatformUserReadRepository userRepository,
            @Lazy final SimpMessagingTemplate messagingTemplate,
            final Clock clock) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.teamMembershipService = teamMembershipService;
        this.teamMemberRepository = teamMemberRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Creates a new standup session with its participants, {@link StandupSessionStatus#PENDING}
     * (US10.1.1).
     *
     * @param teamId                   the owning team's {@code public.teams.id}
     * @param name                     the session name (1-100 chars)
     * @param timePerPersonSeconds     configured speaking time per participant, {@code null} to
     *                                 default to {@link
     *                                 StandupSession#DEFAULT_TIME_PER_PERSON_SECONDS}
     * @param participantTeamMemberIds the participants, in the exact speaking order requested
     * @param callerUserId             the calling user's {@code public.users.id}
     * @param tenantId                 the calling tenant's {@code public.tenants.id}
     * @return the created session as a response record
     * @throws fr.pivot.agilite.exception.TeamNotFoundException if the team does not exist,
     *     belongs to another tenant, or the caller is not one of its members
     * @throws StandupValidationException if {@code name}/{@code timePerPersonSeconds}/a
     *     participant reference is invalid
     */
    @Transactional
    public StandupSessionResponse create(
            final Long teamId,
            final String name,
            final Integer timePerPersonSeconds,
            final List<Long> participantTeamMemberIds,
            final Long callerUserId,
            final Long tenantId) {
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty() || trimmedName.length() > MAX_NAME_LENGTH) {
            throw new StandupValidationException("INVALID_NAME", "name must be 1-100 characters");
        }
        if (participantTeamMemberIds == null || participantTeamMemberIds.isEmpty()) {
            throw new StandupValidationException("EMPTY_PARTICIPANTS", "participantTeamMemberIds must not be empty");
        }
        int resolvedTimePerPerson =
                timePerPersonSeconds != null ? timePerPersonSeconds : StandupSession.DEFAULT_TIME_PER_PERSON_SECONDS;
        if (resolvedTimePerPerson < MIN_TIME_PER_PERSON_SECONDS || resolvedTimePerPerson > MAX_TIME_PER_PERSON_SECONDS) {
            throw new StandupValidationException(
                    "INVALID_TIME_PER_PERSON", "timePerPersonSeconds must be between 30 and 1800");
        }

        PlatformTeam team = teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
        Instant now = clock.instant();
        StandupSession session = new StandupSession(tenantId, team.getId(), trimmedName, resolvedTimePerPerson,
                callerUserId, now);

        int order = 0;
        for (Long teamMemberId : participantTeamMemberIds) {
            session.getParticipants().add(buildParticipant(session, teamMemberId, team.getId(), order++));
        }

        StandupSession saved = sessionRepository.save(session);
        return StandupSessionResponse.from(saved);
    }

    /**
     * Returns a single session if the caller has access to it (US10.1.1).
     *
     * @param sessionId    the session UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the session response
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     */
    @Transactional(readOnly = true)
    public StandupSessionResponse getById(final UUID sessionId, final Long callerUserId, final Long tenantId) {
        return StandupSessionResponse.from(resolveAccessibleSession(sessionId, callerUserId, tenantId));
    }

    /**
     * Lists sessions accessible to the caller (member of the owning team), optionally filtered by
     * {@code teamId}/{@code status} (US10.1.1).
     *
     * @param teamId       an explicit team to scope the listing to, or {@code null} to list across
     *                     every team the caller belongs to
     * @param status       an explicit status filter, or {@code null} for every status
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the matching sessions, {@code createdAt} descending
     * @throws fr.pivot.agilite.exception.TeamNotFoundException if {@code teamId} is given but
     *     does not exist, belongs to another tenant, or the caller is not one of its members
     */
    @Transactional(readOnly = true)
    public List<StandupSessionResponse> list(
            final Long teamId, final StandupSessionStatus status, final Long callerUserId, final Long tenantId) {
        List<Long> teamIds;
        if (teamId != null) {
            teamMembershipService.resolveTeamForCaller(teamId, callerUserId, tenantId);
            teamIds = List.of(teamId);
        } else {
            teamIds = teamMembershipService.listMyTeams(callerUserId, tenantId).stream()
                    .map(TeamResponse::id)
                    .toList();
        }
        List<StandupSession> sessions = status != null
                ? sessionRepository.findAllByTeamIdInAndTenantIdAndStatusOrderByCreatedAtDesc(teamIds, tenantId, status)
                : sessionRepository.findAllByTeamIdInAndTenantIdOrderByCreatedAtDesc(teamIds, tenantId);
        return sessions.stream().map(StandupSessionResponse::from).toList();
    }

    /**
     * Permanently deletes a {@link StandupSessionStatus#PENDING} or {@link
     * StandupSessionStatus#DONE} session and all its participants (US10.1.1).
     *
     * @param sessionId    the session UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     * @throws StandupConflictException if the session is currently {@link
     *     StandupSessionStatus#RUNNING}
     */
    @Transactional
    public void delete(final UUID sessionId, final Long callerUserId, final Long tenantId) {
        StandupSession session = resolveAccessibleSession(sessionId, callerUserId, tenantId);
        if (session.getStatus() == StandupSessionStatus.RUNNING) {
            throw new StandupConflictException("SESSION_RUNNING", "A running session must be ended before deletion");
        }
        sessionRepository.delete(session);
    }

    /**
     * Starts a {@link StandupSessionStatus#PENDING} session: transitions to {@code RUNNING}, the
     * first participant ({@code order == 0}) to {@code SPEAKING}, and broadcasts {@code
     * SESSION_STARTED} (US10.1.2).
     *
     * @param sessionId    the session UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the started session
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     * @throws StandupConflictException if the session is not currently {@link
     *     StandupSessionStatus#PENDING}
     */
    @Transactional
    public StandupSessionResponse start(final UUID sessionId, final Long callerUserId, final Long tenantId) {
        StandupSession session = resolveAccessibleSession(sessionId, callerUserId, tenantId);
        if (session.getStatus() != StandupSessionStatus.PENDING) {
            throw new StandupConflictException("INVALID_SESSION_STATUS", "Session is not PENDING");
        }

        Instant now = clock.instant();
        session.start(now);
        session.setCurrentIndex(0);
        StandupParticipant first = session.getParticipants().stream()
                .filter(p -> p.getParticipantOrder() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Session has no participant at order 0: " + sessionId));
        first.setStatus(StandupParticipantStatus.SPEAKING);
        first.setSpeakingAt(now);

        StandupSession saved = sessionRepository.save(session);
        LOG.info("Standup session started: session={}", sessionId);
        messagingTemplate.convertAndSend(
                StandupDestinations.sessionTopic(sessionId),
                SessionStartedEvent.of(StandupSessionResponse.from(saved)));
        return StandupSessionResponse.from(saved);
    }

    /**
     * Rotates the speaking turn from the current speaker to the next {@code WAITING} participant,
     * or ends the session if none remains (US10.1.2). A double call arriving after the first has
     * already advanced the turn is a silent no-op (idempotent, guarded by {@link
     * StandupParticipantRepository#finishIfSpeaking}).
     *
     * @param sessionId    the session UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the session's current state after the rotation (or no-op)
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     * @throws StandupConflictException if the session is not currently {@link
     *     StandupSessionStatus#RUNNING}
     */
    @Transactional
    public StandupSessionResponse next(final UUID sessionId, final Long callerUserId, final Long tenantId) {
        StandupSession session = resolveAccessibleSession(sessionId, callerUserId, tenantId);
        if (session.getStatus() != StandupSessionStatus.RUNNING) {
            throw new StandupConflictException("INVALID_SESSION_STATUS", "Session is not RUNNING");
        }
        advanceRotation(session, StandupParticipantStatus.DONE, false);
        return getById(sessionId, callerUserId, tenantId);
    }

    /**
     * Skips the current speaker — same rotation as {@link #next}, but the outgoing participant's
     * terminal status is {@link StandupParticipantStatus#SKIPPED} (never counted as speaking time
     * in statistics, US10.3.1) and the "still speaking" broadcast is {@code PARTICIPANT_SKIPPED}
     * instead of {@code PARTICIPANT_CHANGED} (US10.2.2).
     *
     * @param sessionId    the session UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the session's current state after the skip (or no-op)
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     * @throws StandupConflictException if the session is not currently {@link
     *     StandupSessionStatus#RUNNING}
     */
    @Transactional
    public StandupSessionResponse skip(final UUID sessionId, final Long callerUserId, final Long tenantId) {
        StandupSession session = resolveAccessibleSession(sessionId, callerUserId, tenantId);
        if (session.getStatus() != StandupSessionStatus.RUNNING) {
            throw new StandupConflictException("INVALID_SESSION_STATUS", "Session is not RUNNING");
        }
        advanceRotation(session, StandupParticipantStatus.SKIPPED, true);
        return getById(sessionId, callerUserId, tenantId);
    }

    /**
     * System-triggered (timer-based) rotation — a no-op if the session is no longer {@code
     * RUNNING} or no participant is currently {@code SPEAKING} (US10.2.1, called exclusively by
     * {@link StandupTimerScheduler}). No caller identity to check — mirrors {@code
     * RetroPhaseService#autoTransitionToRevue}.
     *
     * @param sessionId the session to auto-advance
     */
    @Transactional
    public void autoAdvance(final UUID sessionId) {
        sessionRepository.findById(sessionId)
                .filter(session -> session.getStatus() == StandupSessionStatus.RUNNING)
                .ifPresent(session -> advanceRotation(session, StandupParticipantStatus.DONE, false));
    }

    /**
     * Ends a {@link StandupSessionStatus#RUNNING} session early, before rotation would have
     * reached the last participant: the current speaker (if any) finishes {@code DONE}, the
     * session transitions to {@code DONE}, and {@code SESSION_ENDED} is broadcast (US10.1.2).
     *
     * @param sessionId    the session UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the ended session
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     * @throws StandupConflictException if the session is not currently {@link
     *     StandupSessionStatus#RUNNING}
     */
    @Transactional
    public StandupSessionResponse end(final UUID sessionId, final Long callerUserId, final Long tenantId) {
        StandupSession session = resolveAccessibleSession(sessionId, callerUserId, tenantId);
        if (session.getStatus() != StandupSessionStatus.RUNNING) {
            throw new StandupConflictException("INVALID_SESSION_STATUS", "Session is not RUNNING");
        }
        Instant now = clock.instant();
        findSpeaking(session).ifPresent(speaking -> {
            speaking.setStatus(StandupParticipantStatus.DONE);
            speaking.setDoneSpeaking(now);
        });
        session.end(now);
        StandupSession saved = sessionRepository.save(session);
        broadcastSessionEnded(saved);
        return StandupSessionResponse.from(saved);
    }

    /**
     * Extends the current speaker's time by the given amount, cumulable across multiple calls
     * (US10.2.2). Broadcasts {@code TIMER_EXTENDED} so every client recalculates its visual timer
     * with the new base.
     *
     * @param sessionId    the session UUID
     * @param seconds      the amount to add — must be {@code 30} or {@code 60}
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the session's current state after the extension
     * @throws StandupValidationException if {@code seconds} is not {@code 30} or {@code 60}
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     * @throws StandupConflictException if the session is not {@code RUNNING} or no participant is
     *     currently {@code SPEAKING}
     */
    @Transactional
    public StandupSessionResponse extend(
            final UUID sessionId, final Integer seconds, final Long callerUserId, final Long tenantId) {
        if (seconds == null || !ALLOWED_EXTEND_SECONDS.contains(seconds)) {
            throw new StandupValidationException("INVALID_EXTEND_SECONDS", "seconds must be 30 or 60");
        }
        StandupSession session = resolveAccessibleSession(sessionId, callerUserId, tenantId);
        if (session.getStatus() != StandupSessionStatus.RUNNING) {
            throw new StandupConflictException("INVALID_SESSION_STATUS", "Session is not RUNNING");
        }
        StandupParticipant speaking = findSpeaking(session)
                .orElseThrow(() -> new StandupConflictException(
                        "INVALID_SESSION_STATUS", "No participant is currently SPEAKING"));
        speaking.addExtraSeconds(seconds);
        participantRepository.save(speaking);

        LOG.info("Standup timer extended: session={} participant={} extraSeconds={}",
                sessionId, speaking.getId(), speaking.getExtraSeconds());
        messagingTemplate.convertAndSend(
                StandupDestinations.sessionTopic(sessionId),
                TimerExtendedEvent.of(sessionId, speaking.getId(), speaking.getExtraSeconds()));
        return getById(sessionId, callerUserId, tenantId);
    }

    /**
     * Rewrites the speaking order of the currently {@code WAITING} tail of the queue (US10.2.2).
     * The requested list must be exactly the set of currently {@code WAITING} participant ids —
     * no more, no fewer, no duplicates.
     *
     * @param sessionId      the session UUID
     * @param participantIds the new order for the {@code WAITING} participants
     * @param callerUserId   the calling user's {@code public.users.id}
     * @param tenantId       the calling tenant's {@code public.tenants.id}
     * @return the session's current state after the reorder
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     * @throws StandupConflictException if the session is not currently {@code RUNNING}
     * @throws StandupValidationException if {@code participantIds} does not exactly match the
     *     currently {@code WAITING} participants
     */
    @Transactional
    public StandupSessionResponse reorder(
            final UUID sessionId, final List<UUID> participantIds, final Long callerUserId, final Long tenantId) {
        StandupSession session = resolveAccessibleSession(sessionId, callerUserId, tenantId);
        if (session.getStatus() != StandupSessionStatus.RUNNING) {
            throw new StandupConflictException("INVALID_SESSION_STATUS", "Session is not RUNNING");
        }
        List<UUID> requested = participantIds == null ? List.of() : participantIds;

        List<StandupParticipant> waiting = session.getParticipants().stream()
                .filter(p -> p.getStatus() == StandupParticipantStatus.WAITING)
                .toList();
        Set<UUID> waitingIds = waiting.stream().map(StandupParticipant::getId).collect(Collectors.toSet());
        Set<UUID> requestedUnique = new HashSet<>(requested);
        boolean sameSize = requested.size() == waiting.size() && requestedUnique.size() == requested.size();
        if (!sameSize || !requestedUnique.equals(waitingIds)) {
            throw new StandupValidationException(
                    "INVALID_REORDER", "participantIds must exactly match the currently WAITING participants");
        }

        Map<UUID, StandupParticipant> byId = new LinkedHashMap<>();
        waiting.forEach(p -> byId.put(p.getId(), p));
        int order = waiting.stream().mapToInt(StandupParticipant::getParticipantOrder).min().orElse(0);
        for (UUID participantId : requested) {
            byId.get(participantId).setParticipantOrder(order++);
        }
        participantRepository.saveAll(byId.values());

        // Re-sort the already-loaded, still-managed collection in place: @OrderBy only shapes the
        // SQL query at initial fetch time, it is not a live invariant Hibernate re-applies after
        // in-memory field mutations within the same persistence context — re-querying wouldn't
        // help either (findById would return this exact same managed instance from the
        // first-level cache, unlike the finishIfSpeaking path, which explicitly clears the
        // persistence context via clearAutomatically).
        session.getParticipants().sort(Comparator.comparingInt(StandupParticipant::getParticipantOrder));

        List<StandupParticipantResponse> allParticipants =
                session.getParticipants().stream().map(StandupParticipantResponse::from).toList();
        LOG.info("Standup participants reordered: session={}", sessionId);
        messagingTemplate.convertAndSend(
                StandupDestinations.sessionTopic(sessionId),
                ParticipantsReorderedEvent.of(sessionId, allParticipants));
        return StandupSessionResponse.from(session);
    }

    /**
     * Checks whether a caller may access a session, without throwing — reuses exactly the same
     * existence/tenant/team-membership resolution as {@link #resolveAccessibleSession}, exposed
     * as a boolean for {@link fr.pivot.agilite.standup.ws.StandupChannelInterceptor} to authorize
     * a STOMP {@code SUBSCRIBE}, mirroring {@code WheelService#isAccessibleTo}.
     *
     * @param sessionId    the session UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return {@code true} if the session exists, belongs to this tenant, and the caller is a
     *     member of its owning team; {@code false} otherwise
     */
    @Transactional(readOnly = true)
    public boolean isAccessibleTo(final UUID sessionId, final Long callerUserId, final Long tenantId) {
        return sessionRepository.findByIdAndTenantId(sessionId, tenantId)
                .filter(session -> teamMemberRepository.existsByTeamIdAndUserId(session.getTeamId(), callerUserId))
                .isPresent();
    }

    /**
     * Shared rotation logic for {@link #next}, {@link #skip} and {@link #autoAdvance}: atomically
     * finishes the current {@code SPEAKING} participant (no-op if none, or if a concurrent call
     * already won the race), then either advances to the next {@code WAITING} participant or ends
     * the session.
     *
     * @param session        the session to rotate, already known {@code RUNNING}
     * @param terminalStatus the outgoing participant's terminal status — {@code DONE} for a
     *                       normal rotation, {@code SKIPPED} for a facilitator skip
     * @param skip           whether this rotation is a skip (US10.2.2) — controls which "still
     *                       speaking" event type is broadcast
     */
    private void advanceRotation(
            final StandupSession session, final StandupParticipantStatus terminalStatus, final boolean skip) {
        Optional<StandupParticipant> speakingOpt = findSpeaking(session);
        if (speakingOpt.isEmpty()) {
            return;
        }
        StandupParticipant speaking = speakingOpt.get();
        Instant now = clock.instant();

        int affected = participantRepository.finishIfSpeaking(speaking.getId(), terminalStatus, now);
        if (affected == 0) {
            return;
        }

        StandupSession refreshed = sessionRepository.findById(session.getId())
                .orElseThrow(() -> new IllegalStateException("Session vanished mid-rotation: " + session.getId()));
        Optional<StandupParticipant> nextParticipant = refreshed.getParticipants().stream()
                .filter(p -> p.getParticipantOrder() == speaking.getParticipantOrder() + 1)
                .filter(p -> p.getStatus() == StandupParticipantStatus.WAITING)
                .findFirst();

        if (nextParticipant.isPresent()) {
            StandupParticipant next = nextParticipant.get();
            next.setStatus(StandupParticipantStatus.SPEAKING);
            next.setSpeakingAt(now);
            refreshed.setCurrentIndex(next.getParticipantOrder());
            StandupSession saved = sessionRepository.save(refreshed);
            broadcastParticipantAdvanced(saved, next, skip);
        } else {
            refreshed.end(now);
            StandupSession saved = sessionRepository.save(refreshed);
            broadcastSessionEnded(saved);
        }
    }

    /**
     * Broadcasts the "still speaking" rotation event — {@code PARTICIPANT_CHANGED} or {@code
     * PARTICIPANT_SKIPPED} depending on how the outgoing participant left.
     *
     * @param session the session that rotated
     * @param next    the new current speaker
     * @param skip    whether the outgoing participant was skipped
     */
    private void broadcastParticipantAdvanced(
            final StandupSession session, final StandupParticipant next, final boolean skip) {
        UUID sessionId = session.getId();
        StandupParticipantResponse response = StandupParticipantResponse.from(next);
        LOG.info("Standup participant advanced: session={} participant={} skip={}", sessionId, next.getId(), skip);
        if (skip) {
            messagingTemplate.convertAndSend(
                    StandupDestinations.sessionTopic(sessionId), ParticipantSkippedEvent.of(sessionId, response));
        } else {
            messagingTemplate.convertAndSend(
                    StandupDestinations.sessionTopic(sessionId), ParticipantChangedEvent.of(sessionId, response));
        }
    }

    /**
     * Broadcasts {@code SESSION_ENDED} for a session that just reached its terminal status.
     *
     * @param session the ended session, already persisted with {@code endedAt} set
     */
    private void broadcastSessionEnded(final StandupSession session) {
        long durationSeconds = session.getStartedAt() == null
                ? 0
                : Duration.between(session.getStartedAt(), session.getEndedAt()).getSeconds();
        LOG.info("Standup session ended: session={} durationSeconds={}", session.getId(), durationSeconds);
        messagingTemplate.convertAndSend(
                StandupDestinations.sessionTopic(session.getId()),
                SessionEndedEvent.of(session.getId(), durationSeconds, session.getParticipants().size()));
    }

    /**
     * Resolves a session by id and tenant, then verifies the caller is a member of its team.
     *
     * @param sessionId    the session UUID
     * @param callerUserId the calling user's {@code public.users.id}
     * @param tenantId     the calling tenant's {@code public.tenants.id}
     * @return the resolved session
     * @throws StandupSessionNotFoundException if the session does not exist, belongs to another
     *     tenant, or the caller is not a member of its team
     */
    private StandupSession resolveAccessibleSession(final UUID sessionId, final Long callerUserId, final Long tenantId) {
        StandupSession session = sessionRepository.findByIdAndTenantId(sessionId, tenantId)
                .orElseThrow(() -> new StandupSessionNotFoundException(sessionId));
        if (!teamMemberRepository.existsByTeamIdAndUserId(session.getTeamId(), callerUserId)) {
            throw new StandupSessionNotFoundException(sessionId);
        }
        return session;
    }

    /**
     * Finds the currently {@code SPEAKING} participant of a session, if any.
     *
     * @param session the session to search
     * @return the speaking participant, or empty
     */
    private static Optional<StandupParticipant> findSpeaking(final StandupSession session) {
        return session.getParticipants().stream()
                .filter(p -> p.getStatus() == StandupParticipantStatus.SPEAKING)
                .findFirst();
    }

    /**
     * Validates and builds a single participant, resolving its display name server-side.
     *
     * @param session      the owning session (not yet persisted)
     * @param teamMemberId the requested {@code public.team_members.id}
     * @param teamId       the session's team, the reference must belong to this team
     * @param order        the {@code 0}-based speaking-turn order
     * @return the built participant
     * @throws StandupValidationException if the reference does not resolve to a member of the
     *     team
     */
    private StandupParticipant buildParticipant(
            final StandupSession session, final Long teamMemberId, final Long teamId, final int order) {
        PlatformTeamMember member = teamMemberRepository.findByIdAndTeamId(teamMemberId, teamId)
                .orElseThrow(() -> new StandupValidationException(
                        "INVALID_PARTICIPANT", "teamMemberId does not belong to this team: " + teamMemberId));
        PlatformUser user = userRepository.findById(member.getUserId())
                .orElseThrow(() -> new StandupValidationException(
                        "INVALID_PARTICIPANT", "team member's user could not be resolved"));
        String name = teamMembershipService.resolveDisplayName(user);
        return new StandupParticipant(session, teamMemberId, name, order);
    }
}
