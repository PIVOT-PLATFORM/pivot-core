package fr.pivot.collaboratif.session;

/**
 * Lifecycle status of a {@link Session} (US19.1.1/US19.1.2).
 *
 * <p>Strict state machine: {@code DRAFT -> LIVE -> {PAUSED <-> LIVE} -> COMPLETED}. Every other
 * transition is rejected as {@code INVALID_SESSION_TRANSITION} — see {@code SessionService}.
 */
public enum SessionStatus {
    DRAFT,
    LIVE,
    PAUSED,
    COMPLETED
}
