package fr.pivot.agilite.standup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Periodically checks every {@link StandupSessionStatus#RUNNING} session for a {@code SPEAKING}
 * participant whose configured speaking time has elapsed, triggering the same rotation as a
 * manual {@code POST .../next} (US10.2.1 AC: "when {@code speakingAt + timePerPersonSeconds +
 * extraSeconds} est dépassé, then un scheduler périodique déclenche automatiquement la même
 * transition que {@code POST .../next}").
 *
 * <p>Exact calque of {@code fr.pivot.agilite.retro.phase.RetroPhaseScheduler} — {@code
 * fixedDelay} poll (not a per-second tick), injectable {@link Clock} for deterministic tests,
 * never {@code Instant.now()} in-line. Unlike {@code RetroPhaseScheduler} (whose candidate
 * sessions carry only scalar fields), the candidate here — "which participant is speaking, since
 * when" — lives on a {@code LAZY} child collection, so this class reads {@link
 * StandupParticipantRepository#findRunningSpeakingDeadlines()}'s plain scalar projection instead
 * of the full {@link StandupSession} entity graph, sidestepping any need for its own transaction
 * at this call site (the actual write happens inside {@link StandupSessionService#autoAdvance}'s
 * own separate {@code @Transactional} method).
 *
 * <p><strong>Extension-aware deadline (US10.2.2).</strong> The deadline always includes the
 * current speaker's cumulative {@code extraSeconds} — a facilitator {@code extend} genuinely
 * pushes back the automatic rotation, never just the client-side visual countdown.
 */
@Component
public class StandupTimerScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(StandupTimerScheduler.class);

    private final StandupParticipantRepository participantRepository;
    private final StandupSessionService sessionService;
    private final Clock clock;

    /**
     * Constructs the scheduler with its required dependencies.
     *
     * @param participantRepository standup participant persistence, used to find {@code
     *                              RUNNING}/{@code SPEAKING} candidates via a scalar projection
     * @param sessionService        performs the actual rotation and broadcast
     * @param clock                 the shared clock, overridable in tests
     */
    public StandupTimerScheduler(
            final StandupParticipantRepository participantRepository,
            final StandupSessionService sessionService,
            final Clock clock) {
        this.participantRepository = participantRepository;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    /**
     * Scans every currently-{@code SPEAKING} participant of a {@code RUNNING} session and
     * auto-rotates those whose speaking time has elapsed.
     */
    @Scheduled(fixedDelayString = "${pivot.agilite.standup.timer-scheduler.fixed-delay-ms:2000}")
    public void checkSpeakingTimers() {
        Instant now = clock.instant();
        for (StandupParticipantRepository.SpeakingDeadlineRow row : participantRepository.findRunningSpeakingDeadlines()) {
            Instant deadline = row.getSpeakingAt()
                    .plusSeconds((long) row.getTimePerPersonSeconds() + row.getExtraSeconds());
            if (!deadline.isAfter(now)) {
                LOG.debug("Speaking timer elapsed for session={}, auto-advancing", row.getSessionId());
                sessionService.autoAdvance(row.getSessionId());
            }
        }
    }
}
