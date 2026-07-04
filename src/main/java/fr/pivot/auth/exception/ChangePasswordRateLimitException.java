package fr.pivot.auth.exception;

/**
 * Thrown when the {@code POST /api/account/password} rate limit (5 attempts / 15 min, keyed
 * independently by user id and by client IP) is exceeded.
 *
 * <p>Unlike the generic {@link RateLimitException} (which is translated into a self-describing
 * {@code {"code":"RATE_LIMITED", ...}} body elsewhere in the app), this exception is translated
 * into a body carrying the exact same message text as the "current password incorrect" 401
 * response. This is deliberate anti-enumeration: an attacker probing the endpoint must not be
 * able to tell, from the response body, whether a given attempt failed because the password was
 * wrong or because the rate limit kicked in. The 429 status code and {@code Retry-After} header
 * are still present (required by the AC), only the message text is shared.
 */
public class ChangePasswordRateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    /**
     * @param message           text identical to the "current password incorrect" 401 message
     * @param retryAfterSeconds remaining lockout duration in seconds (≥ 1)
     */
    public ChangePasswordRateLimitException(final String message, final long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** @return remaining lockout duration in seconds, used for the {@code Retry-After} header */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
