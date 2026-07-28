package fr.pivot.collaboratif.session.postitrush;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodically drives every active POST-IT RUSH round's server-authoritative clock (US47.2.1):
 * spawns due post-its, expires unclaimed ones past their lifespan, ends a round whose duration has
 * elapsed, and flushes any leaderboard broadcast whose throttle window has since passed. The
 * client never decides any of this — it only ever reacts to the STOMP broadcasts this scheduler
 * (via {@link PostitRushActivityService}) triggers.
 *
 * <p>Same {@code fixedDelay} poll-over-a-deadline-column shape as {@code
 * fr.pivot.agilite.standup.StandupTimerScheduler}/{@code fr.pivot.agilite.retro.phase.RetroPhaseScheduler}
 * (not a per-second tick, not one long-lived timer thread per round). Uses {@code Instant.now()}
 * directly rather than an injected {@code Clock} bean — this module (unlike agilite) has none
 * registered, and {@code QuizActivityService}'s own timing (US19.3.1) follows the identical
 * direct-{@code Instant.now()} convention; tests control timing by back-dating persisted
 * timestamps instead (see {@code QuizActivityServiceTest}).
 */
@Component
public class PostitRushScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PostitRushScheduler.class);

    private final SessionPostitRushRoundRepository roundRepository;
    private final PostitRushActivityService activityService;

    /**
     * Constructs the scheduler with its required dependencies.
     *
     * @param roundRepository repository used to scan every currently active round
     * @param activityService performs the actual spawn/expire/end/leaderboard-flush operations
     */
    public PostitRushScheduler(
            final SessionPostitRushRoundRepository roundRepository,
            final PostitRushActivityService activityService) {
        this.roundRepository = roundRepository;
        this.activityService = activityService;
    }

    /**
     * Scans every active round and advances its server-authoritative state.
     */
    @Scheduled(fixedDelayString = "${pivot.collaboratif.postit-rush.scheduler.fixed-delay-ms:300}")
    public void tick() {
        Instant now = Instant.now();
        for (SessionPostitRushRound round : roundRepository.findAllByEndedAtIsNull()) {
            try {
                advance(round, now);
            } catch (RuntimeException e) {
                LOG.warn("POST-IT RUSH scheduler tick failed for round={}", round.getId(), e);
            }
        }
    }

    private void advance(final SessionPostitRushRound round, final Instant now) {
        if (activityService.endRoundIfElapsed(round, now)) {
            return;
        }
        activityService.expireDueSpawns(round, now);
        activityService.generateSpawnIfDue(round, now);
        activityService.flushLeaderboardIfDue(round.getSessionId(), round, now);
    }
}
