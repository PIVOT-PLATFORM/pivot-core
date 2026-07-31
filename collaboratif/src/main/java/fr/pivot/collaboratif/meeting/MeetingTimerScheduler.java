package fr.pivot.collaboratif.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Drives the 1 Hz {@code TIMER_TICK} broadcast for every {@code IN_PROGRESS} meeting (US12.2.1
 * AC-02/AC-04/AC-05) — a periodic reconciliation signal, never the authority (see {@code
 * MeetingTimerMath}'s JavaDoc). Mirrors {@code fr.pivot.agilite.standup.StandupTimerScheduler}'s
 * scalar-id-projection + per-entity-transactional-call shape: {@link
 * MeetingRepository#findIdsByStatus} returns bare ids (no entity graph, no shared transaction),
 * and each is ticked by its own call to {@link MeetingAnimationService#tick}, which opens and
 * commits its own short-lived transaction — one meeting's tick failing or taking long never
 * blocks or corrupts another's.
 */
@Component
public class MeetingTimerScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(MeetingTimerScheduler.class);

    private final MeetingRepository meetingRepository;
    private final MeetingAnimationService animationService;

    /**
     * Creates the scheduler with its required dependencies.
     *
     * @param meetingRepository repository used to find {@code IN_PROGRESS} meeting ids
     * @param animationService  performs each meeting's actual tick (timer broadcast + expiry
     *                          handling)
     */
    public MeetingTimerScheduler(
            final MeetingRepository meetingRepository, final MeetingAnimationService animationService) {
        this.meetingRepository = meetingRepository;
        this.animationService = animationService;
    }

    /**
     * Ticks every currently {@code IN_PROGRESS} meeting. Configurable cadence (default 1000 ms,
     * i.e. 1 Hz per the "Timer 1 s" implementation note); a per-meeting failure is caught and
     * logged so it can never abort the remaining meetings' ticks within the same run.
     */
    @Scheduled(fixedRateString = "${pivot.collaboratif.meeting.timer-scheduler.fixed-rate-ms:1000}")
    public void tick() {
        List<UUID> meetingIds = meetingRepository.findIdsByStatus(MeetingStatus.IN_PROGRESS);
        for (UUID meetingId : meetingIds) {
            try {
                animationService.tick(meetingId);
            } catch (Exception e) {
                LOG.warn("Failed to tick meeting={}", meetingId, e);
            }
        }
    }
}
