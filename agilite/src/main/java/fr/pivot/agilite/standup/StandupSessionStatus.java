package fr.pivot.agilite.standup;

/**
 * Lifecycle status of a {@link StandupSession} (US10.1.1/US10.1.2).
 *
 * <p>A session is created {@link #PENDING}, transitions to {@link #RUNNING} on {@code
 * POST .../start}, and reaches the terminal {@link #DONE} either via {@code POST .../next} on the
 * last participant, {@code POST .../end} (early end), or {@link StandupTimerScheduler}'s automatic
 * expiry rotation (US10.2.1) — never transitions back.
 */
public enum StandupSessionStatus {

    /** Created, not yet started — no participant is {@code SPEAKING} yet. */
    PENDING,

    /** Started — exactly one participant is currently {@code SPEAKING} (or the session is empty
     * of {@code WAITING} participants and about to end on the next rotation). */
    RUNNING,

    /** Terminal — every participant has spoken, been skipped, or the session was ended early. */
    DONE
}
