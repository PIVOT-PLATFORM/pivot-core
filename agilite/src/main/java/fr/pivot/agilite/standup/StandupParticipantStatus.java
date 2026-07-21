package fr.pivot.agilite.standup;

/**
 * Turn-taking status of a {@link StandupParticipant} within its session (US10.1.1/US10.1.2/
 * US10.2.2).
 *
 * <p>Every participant starts {@link #WAITING}. Exactly one participant of a {@code RUNNING}
 * session is {@link #SPEAKING} at a time. A rotation ({@code next}, the {@link
 * StandupTimerScheduler} auto-expiry, or an early {@code end}) moves the current speaker to
 * {@link #DONE}; {@code skip} (US10.2.2) moves it to {@link #SKIPPED} instead — the sole
 * distinction being that a {@code SKIPPED} participant's speaking duration always counts as zero
 * in statistics (US10.3.1), never derived from its timestamps.
 */
public enum StandupParticipantStatus {

    /** Has not yet spoken, not currently speaking. */
    WAITING,

    /** Currently speaking — at most one per session at any time. */
    SPEAKING,

    /** Finished speaking normally (rotated past via {@code next}/auto-expiry/{@code end}). */
    DONE,

    /** Skipped by the facilitator (US10.2.2) — never counted as speaking time in statistics. */
    SKIPPED
}
