package fr.pivot.collaboratif.meeting;

import java.time.Duration;
import java.time.Instant;

/**
 * Server-authoritative timer computation shared by {@link MeetingAnimationService} (used both by
 * {@code GET .../live}, AC-07, and by every state-changing action's broadcast) and {@code
 * MeetingTimerScheduler} (the 1 Hz {@code TIMER_TICK}, AC-02/AC-04/AC-05) — one implementation
 * guarantees both call sites can never compute a different {@code elapsedSeconds} for the same
 * instant (AC-S4: "aucune valeur de temps envoyée par un client n'est acceptée ni rediffusée" —
 * the flip side of that guarantee is that the server's own two computation sites must agree).
 *
 * <p>Deliberately stateless/static: every input ({@link AgendaItem#getCurrentItemStartedAt()},
 * {@link AgendaItem#getDurationMinutes()}, and the caller-supplied {@code now}) is already
 * server-authoritative by construction — there is nothing here that benefits from being an
 * injectable bean.
 */
final class MeetingTimerMath {

    private MeetingTimerMath() {
    }

    /**
     * Computes the current timer snapshot for {@code item} at {@code now}.
     *
     * @param item the agenda item, expected {@link AgendaItemStatus#CURRENT} with a non-{@code
     *             null} {@link AgendaItem#getCurrentItemStartedAt()} — a defensive zero snapshot
     *             is returned otherwise (e.g. a meeting with no current item yet)
     * @param now  server-authoritative "now", from the shared {@code meetOpsClock}
     * @return the computed snapshot
     */
    static Snapshot compute(final AgendaItem item, final Instant now) {
        if (item == null || item.getCurrentItemStartedAt() == null) {
            return new Snapshot(0, item == null ? 0 : item.getDurationMinutes() * 60L, false, 0);
        }
        long allottedSeconds = item.getDurationMinutes() * 60L;
        long elapsedSeconds = Duration.between(item.getCurrentItemStartedAt(), now).getSeconds();
        long remainingSeconds = allottedSeconds - elapsedSeconds;
        boolean overtime = remainingSeconds < 0;
        long overtimeSeconds = overtime ? -remainingSeconds : 0;
        return new Snapshot(elapsedSeconds, remainingSeconds, overtime, overtimeSeconds);
    }

    /**
     * A computed timer snapshot, always server-derived (AC-S4) — every field of {@code
     * TimerTickEvent}/{@code MeetingLiveStateDto} that describes the current item's timer state
     * traces back to one of these.
     *
     * @param elapsedSeconds   seconds since the item became current
     * @param remainingSeconds {@code allottedSeconds - elapsedSeconds}; negative once in overtime
     *                         (AC-04)
     * @param overtime         {@code true} once {@code remainingSeconds} goes negative
     * @param overtimeSeconds  {@code max(0, -remainingSeconds)} — always {@code > 0} exactly when
     *                         {@code overtime} is {@code true} (AC-04)
     */
    record Snapshot(long elapsedSeconds, long remainingSeconds, boolean overtime, long overtimeSeconds) {
    }
}
