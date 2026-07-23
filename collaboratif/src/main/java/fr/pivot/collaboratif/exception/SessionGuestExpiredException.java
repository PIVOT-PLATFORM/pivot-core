package fr.pivot.collaboratif.exception;

/**
 * Thrown when a guest participant's {@code guestToken} (US19.2.1) does not resolve to a currently
 * valid, non-expired guest session — either it was never issued, it has expired (missed
 * heartbeat past the rolling TTL), or it is being used against a session other than the one it
 * was issued for.
 *
 * <p>Mapped to HTTP 401 with code {@code GUEST_SESSION_EXPIRED} by {@code
 * CollaboratifExceptionHandler} — these causes are deliberately never distinguished, matching
 * this session's established anti-enumeration posture.
 */
public class SessionGuestExpiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for a guest session that is no longer valid.
     */
    public SessionGuestExpiredException() {
        super("Guest session expired or invalid");
    }
}
