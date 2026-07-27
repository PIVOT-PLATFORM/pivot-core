package fr.pivot.collaboratif.session.postitrush;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.PostitNotFoundException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Participant;
import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.ParticipantRole;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.postitrush.dto.ClickPostitRequest;
import fr.pivot.collaboratif.session.postitrush.dto.ClickPostitResponse;
import fr.pivot.collaboratif.session.postitrush.dto.LeaderboardUpdatedEvent;
import fr.pivot.collaboratif.session.postitrush.dto.LivePostitDto;
import fr.pivot.collaboratif.session.postitrush.dto.PostitClaimedEvent;
import fr.pivot.collaboratif.session.postitrush.dto.PostitExpiredEvent;
import fr.pivot.collaboratif.session.postitrush.dto.PostitRushLeaderboardEntry;
import fr.pivot.collaboratif.session.postitrush.dto.PostitRushResultsDto;
import fr.pivot.collaboratif.session.postitrush.dto.PostitRushStandingEntry;
import fr.pivot.collaboratif.session.postitrush.dto.PostitRushStateDto;
import fr.pivot.collaboratif.session.postitrush.dto.PostitSpawnedEvent;
import fr.pivot.collaboratif.session.postitrush.dto.RoundEndedEvent;
import fr.pivot.collaboratif.session.postitrush.dto.RoundStartedEvent;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for the POST-IT RUSH activity type (US47.2.1) — a server-timed, server-scored
 * real-time mini-game (E47/F47.2). Aligned to the same canonical shared session/join/participant
 * socle and server-authority pattern as {@code QuizActivityService} (US19.3.1): the backend alone
 * owns the round clock, the spawn schedule, and every scoring decision — a client never sends
 * anything but which post-it it clicked.
 *
 * <p>{@link #generateSpawnIfDue}/{@link #expireDueSpawns}/{@link #endRoundIfElapsed}/
 * {@link #flushLeaderboardIfDue} are package-private — driven by {@link PostitRushScheduler}'s
 * periodic poll, the same "poll a deadline column" pattern as {@code StandupTimerScheduler}/
 * {@code RetroPhaseScheduler}, rather than one long-lived timer thread per round.
 */
@Service
public class PostitRushActivityService {

    private final SessionPostitRushRoundRepository roundRepository;
    private final SessionPostitRushSpawnRepository spawnRepository;
    private final SessionPostitRushParticipantRoundRepository participantRoundRepository;
    private final ParticipantRepository participantRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * Creates the service with its required dependencies.
     *
     * @param roundRepository            repository for round lifecycle
     * @param spawnRepository            repository for post-it spawns
     * @param participantRoundRepository repository for per-round score/combo state
     * @param participantRepository      repository used to resolve leaderboard display names and
     *                                    active-player counts (softCap/hardCap degradation)
     * @param messagingTemplate          STOMP broadcaster
     * @param objectMapper                JSON deserializer for the session's {@code config}
     */
    public PostitRushActivityService(
            final SessionPostitRushRoundRepository roundRepository,
            final SessionPostitRushSpawnRepository spawnRepository,
            final SessionPostitRushParticipantRoundRepository participantRoundRepository,
            final ParticipantRepository participantRepository,
            final SimpMessagingTemplate messagingTemplate,
            final ObjectMapper objectMapper) {
        this.roundRepository = roundRepository;
        this.spawnRepository = spawnRepository;
        this.participantRoundRepository = participantRoundRepository;
        this.participantRepository = participantRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Starts a new round — host/facilitator action (US47.2.1), owner-or-admin already enforced by
     * the caller via {@code SessionAccessService}.
     *
     * @param session the LIVE {@code POSTIT_RUSH} session
     * @throws InvalidSessionStatusException if the session is not a LIVE {@code POSTIT_RUSH}
     * @throws SessionConflictException      if a round is already active
     */
    @Transactional
    public void startRound(final Session session) {
        requireType(session);
        if (session.getStatus() != SessionStatus.LIVE) {
            throw new InvalidSessionStatusException("Session is not LIVE");
        }
        if (roundRepository.findBySessionIdAndEndedAtIsNull(session.getId()).isPresent()) {
            throw new SessionConflictException("ROUND_IN_PROGRESS", "A round is already active");
        }

        Instant now = Instant.now();
        int durationSeconds = readDurationSeconds(session);
        int roundNumber = (int) roundRepository.countBySessionId(session.getId()) + 1;
        SessionPostitRushRound round =
                new SessionPostitRushRound(session.getId(), roundNumber, durationSeconds, now);
        roundRepository.save(round);

        broadcast(session.getId(), new RoundStartedEvent(round.getId(), durationSeconds, now));
    }

    /**
     * Records a participant's click on a post-it (US47.2.1) — the request carries only the
     * {@code postitId}; the server alone decides liveness, points and combo.
     *
     * @param session       the LIVE {@code POSTIT_RUSH} session
     * @param participantId the clicking participant's id
     * @param request       the click request
     * @return the clicking participant's updated score/combo state
     * @throws InvalidSessionStatusException if the session is not a LIVE {@code POSTIT_RUSH}
     * @throws SessionConflictException      if no round is active ({@code ROUND_NOT_ACTIVE}), or
     *                                        the post-it is already claimed/expired
     *                                        ({@code POSTIT_UNAVAILABLE}, also a combo-breaking
     *                                        miss for the clicker)
     * @throws PostitNotFoundException       if the {@code postitId} does not resolve within the
     *                                        active round
     */
    @Transactional
    public ClickPostitResponse click(
            final Session session, final UUID participantId, final ClickPostitRequest request) {
        requireType(session);
        if (session.getStatus() != SessionStatus.LIVE) {
            throw new InvalidSessionStatusException("Session is not LIVE");
        }
        Instant now = Instant.now();
        SessionPostitRushRound round = roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())
                .filter(r -> !r.isElapsed(now))
                .orElseThrow(() -> new SessionConflictException("ROUND_NOT_ACTIVE", "No round is currently active"));

        SessionPostitRushSpawn spawn = spawnRepository.findByIdAndRoundId(request.postitId(), round.getId())
                .orElseThrow(PostitNotFoundException::new);

        SessionPostitRushParticipantRound state = participantRoundRepository
                .findByIdRoundIdAndIdParticipantId(round.getId(), participantId)
                .orElseGet(() -> new SessionPostitRushParticipantRound(round.getId(), participantId));

        if (!spawn.isLiveAt(now)) {
            state.registerMiss();
            participantRoundRepository.save(state);
            throw new SessionConflictException("POSTIT_UNAVAILABLE", "Post-it is no longer available");
        }

        spawn.claim(participantId, now);
        spawnRepository.save(spawn);
        int pointsAwarded = state.registerHit(now);
        participantRoundRepository.save(state);

        broadcast(session.getId(), new PostitClaimedEvent(spawn.getId(), participantId));
        round.setLeaderboardDirty(true);
        flushLeaderboardIfDue(session.getId(), round, now);

        return new ClickPostitResponse(
                pointsAwarded,
                SessionPostitRushParticipantRound.multiplierFor(state.getCurrentCombo()),
                state.getScore(),
                state.getCurrentCombo(),
                state.getHits());
    }

    /**
     * Builds the participant-safe reconnect snapshot (US47.2.1) — remaining time, currently-live
     * post-its, and the caller's own score/combo. A pure read: fetching it never double-counts
     * prior clicks.
     *
     * @param session       the {@code POSTIT_RUSH} session
     * @param participantId the requesting participant's id
     * @return the reconnect snapshot
     * @throws InvalidSessionStatusException if the session is not a {@code POSTIT_RUSH}
     */
    @Transactional(readOnly = true)
    public PostitRushStateDto getState(final Session session, final UUID participantId) {
        requireType(session);
        Instant now = Instant.now();
        Optional<SessionPostitRushRound> activeRound = roundRepository
                .findBySessionIdAndEndedAtIsNull(session.getId())
                .filter(r -> !r.isElapsed(now));

        // Checked on Optional#isEmpty(), never by testing a derived id for null — the round
        // being present is what matters, regardless of what its id happens to be.
        SessionPostitRushParticipantRound myState = activeRound.isEmpty() ? null
                : participantRoundRepository
                        .findByIdRoundIdAndIdParticipantId(activeRound.get().getId(), participantId)
                        .orElse(null);
        int myScore = myState == null ? 0 : myState.getScore();
        int myCombo = myState == null ? 0 : myState.getCurrentCombo();
        int myBest = myState == null ? 0 : myState.getBestCombo();
        int myHits = myState == null ? 0 : myState.getHits();

        if (activeRound.isEmpty()) {
            return new PostitRushStateDto(false, null, null, List.of(), myScore, myCombo, myBest, myHits);
        }

        SessionPostitRushRound round = activeRound.get();
        int remaining = (int) Math.max(0,
                round.getDurationSeconds() - Duration.between(round.getStartedAt(), now).getSeconds());
        List<LivePostitDto> live = spawnRepository
                .findAllByRoundIdAndClaimedByIsNullAndExpiredFalse(round.getId())
                .stream()
                .filter(s -> s.isLiveAt(now))
                .map(s -> new LivePostitDto(s.getId(), s.getX(), s.getY(), s.getColorKey(), s.remainingMs(now)))
                .toList();

        return new PostitRushStateDto(true, round.getId(), remaining, live, myScore, myCombo, myBest, myHits);
    }

    /**
     * Returns the final standings of the most recently played round (US47.2.1).
     *
     * @param session the {@code POSTIT_RUSH} session
     * @return the final standings
     * @throws InvalidSessionStatusException if the session is not a {@code POSTIT_RUSH}
     * @throws SessionValidationException    if no round has ever been played
     */
    @Transactional(readOnly = true)
    public PostitRushResultsDto getResults(final Session session) {
        requireType(session);
        SessionPostitRushRound round = roundRepository
                .findFirstBySessionIdOrderByRoundNumberDesc(session.getId())
                .orElseThrow(() -> new SessionValidationException("NO_ROUND_PLAYED", "No round has been played yet"));

        List<SessionPostitRushParticipantRound> rows = participantRoundRepository.findAllByIdRoundId(round.getId());
        Map<UUID, String> names = displayNames(rows.stream().map(SessionPostitRushParticipantRound::getParticipantId).toList());

        List<SessionPostitRushParticipantRound> sorted = new ArrayList<>(rows);
        sorted.sort(scoreThenEarliestComparator());

        List<PostitRushStandingEntry> standings = new ArrayList<>(sorted.size());
        int rank = 1;
        for (SessionPostitRushParticipantRound row : sorted) {
            standings.add(new PostitRushStandingEntry(
                    rank++, row.getParticipantId(), names.get(row.getParticipantId()),
                    row.getScore(), row.getHits(), row.getBestCombo()));
        }
        return new PostitRushResultsDto(standings);
    }

    // --- scheduler-driven internals (package-private, see PostitRushScheduler) -----------------

    /**
     * Generates a new post-it if this round's next-spawn deadline has passed, and reschedules the
     * following one — gap widened above softCap (progressive degradation).
     *
     * @param round the active round
     * @param now   the current instant
     */
    void generateSpawnIfDue(final SessionPostitRushRound round, final Instant now) {
        if (round.getNextSpawnAt() == null || round.getNextSpawnAt().isAfter(now)) {
            return;
        }
        boolean degraded = activePlayerCount(round.getSessionId()) > PostitRushConstants.SOFT_CAP;

        double x = randomPercentage();
        double y = randomPercentage();
        String colorKey = PostitRushConstants.COLOR_KEYS[random.nextInt(PostitRushConstants.COLOR_KEYS.length)];
        int lifespanMs = randomInt(PostitRushConstants.LIFESPAN_MIN_MS, PostitRushConstants.LIFESPAN_MAX_MS);

        SessionPostitRushSpawn spawn = new SessionPostitRushSpawn(round.getId(), x, y, colorKey, now, lifespanMs);
        spawnRepository.save(spawn);
        broadcast(round.getSessionId(), new PostitSpawnedEvent(spawn.getId(), x, y, colorKey, now, lifespanMs));

        int gapMin = degraded ? PostitRushConstants.SPAWN_GAP_MIN_MS_DEGRADED : PostitRushConstants.SPAWN_GAP_MIN_MS;
        int gapMax = degraded ? PostitRushConstants.SPAWN_GAP_MAX_MS_DEGRADED : PostitRushConstants.SPAWN_GAP_MAX_MS;
        round.setNextSpawnAt(now.plusMillis(randomInt(gapMin, gapMax)));
        roundRepository.save(round);
    }

    /**
     * Expires every unresolved spawn of a round whose lifespan has elapsed, broadcasting
     * {@code POSTIT_EXPIRED} for each.
     *
     * @param round the active round
     * @param now   the current instant
     */
    void expireDueSpawns(final SessionPostitRushRound round, final Instant now) {
        for (SessionPostitRushSpawn spawn
                : spawnRepository.findAllByRoundIdAndClaimedByIsNullAndExpiredFalse(round.getId())) {
            if (spawn.isPastLifespan(now)) {
                spawn.expire();
                spawnRepository.save(spawn);
                broadcast(round.getSessionId(), new PostitExpiredEvent(spawn.getId()));
            }
        }
    }

    /**
     * Ends a round whose duration has elapsed, broadcasting {@code ROUND_ENDED}.
     *
     * @param round the active round
     * @param now   the current instant
     * @return {@code true} if the round was just ended
     */
    boolean endRoundIfElapsed(final SessionPostitRushRound round, final Instant now) {
        if (!round.isElapsed(now)) {
            return false;
        }
        round.end(now);
        roundRepository.save(round);
        broadcast(round.getSessionId(), new RoundEndedEvent(round.getId()));
        return true;
    }

    /**
     * Broadcasts {@code LEADERBOARD_UPDATED} if a score changed and the throttle window (widened
     * above softCap) has elapsed since the last broadcast; otherwise leaves the round marked dirty
     * for the next scheduler tick or click to flush.
     *
     * @param sessionId the owning session's id
     * @param round     the active round
     * @param now       the current instant
     */
    void flushLeaderboardIfDue(final UUID sessionId, final SessionPostitRushRound round, final Instant now) {
        if (!round.isLeaderboardDirty()) {
            return;
        }
        boolean degraded = activePlayerCount(sessionId) > PostitRushConstants.SOFT_CAP;
        Duration throttle = degraded
                ? PostitRushConstants.LEADERBOARD_THROTTLE_DEGRADED : PostitRushConstants.LEADERBOARD_THROTTLE;
        Instant last = round.getLeaderboardBroadcastAt();
        if (last != null && Duration.between(last, now).compareTo(throttle) < 0) {
            return;
        }
        Integer topN = degraded ? PostitRushConstants.LEADERBOARD_TOP_N_DEGRADED : null;
        broadcast(sessionId, new LeaderboardUpdatedEvent(computeLeaderboard(round.getId(), topN)));
        round.setLeaderboardBroadcastAt(now);
        round.setLeaderboardDirty(false);
        roundRepository.save(round);
    }

    // --- internals ------------------------------------------------------------------------------

    private List<PostitRushLeaderboardEntry> computeLeaderboard(final UUID roundId, final Integer topN) {
        List<SessionPostitRushParticipantRound> rows = participantRoundRepository.findAllByIdRoundId(roundId);
        List<SessionPostitRushParticipantRound> sorted = new ArrayList<>(rows);
        sorted.sort(scoreThenEarliestComparator());
        if (topN != null && sorted.size() > topN) {
            sorted = sorted.subList(0, topN);
        }
        Map<UUID, String> names = displayNames(
                sorted.stream().map(SessionPostitRushParticipantRound::getParticipantId).toList());

        List<PostitRushLeaderboardEntry> entries = new ArrayList<>(sorted.size());
        int rank = 1;
        for (SessionPostitRushParticipantRound row : sorted) {
            entries.add(new PostitRushLeaderboardEntry(
                    row.getParticipantId(), names.get(row.getParticipantId()), row.getScore(), rank++));
        }
        return entries;
    }

    private Comparator<SessionPostitRushParticipantRound> scoreThenEarliestComparator() {
        return Comparator.comparingInt(SessionPostitRushParticipantRound::getScore).reversed()
                .thenComparing(row -> row.getScoreReachedAt() == null ? Instant.MAX : row.getScoreReachedAt());
    }

    private Map<UUID, String> displayNames(final List<UUID> participantIds) {
        Map<UUID, String> names = new HashMap<>();
        for (Participant participant : participantRepository.findAllById(participantIds)) {
            names.put(participant.getId(), participant.getDisplayName());
        }
        return names;
    }

    private long activePlayerCount(final UUID sessionId) {
        return participantRepository.countBySessionIdAndRole(sessionId, ParticipantRole.PLAYER);
    }

    private double randomPercentage() {
        return Math.round(random.nextDouble() * 10000) / 100.0;
    }

    private int randomInt(final int minInclusive, final int maxInclusive) {
        return minInclusive + random.nextInt(maxInclusive - minInclusive + 1);
    }

    private int readDurationSeconds(final Session session) {
        JsonNode node = config(session).get("durationSeconds");
        return node != null ? node.asInt(PostitRushConstants.DEFAULT_DURATION_SECONDS)
                : PostitRushConstants.DEFAULT_DURATION_SECONDS;
    }

    private JsonNode config(final Session session) {
        try {
            return objectMapper.readTree(session.getConfig() == null ? "{}" : session.getConfig());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read session config", e);
        }
    }

    private void requireType(final Session session) {
        if (session.getType() != SessionType.POSTIT_RUSH) {
            throw new InvalidSessionStatusException("Session is not a POSTIT_RUSH session");
        }
    }

    private void broadcast(final UUID sessionId, final Object event) {
        messagingTemplate.convertAndSend(SessionDestinations.topicFor(sessionId), event);
    }
}
