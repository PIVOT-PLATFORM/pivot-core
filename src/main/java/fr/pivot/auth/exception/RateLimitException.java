package fr.pivot.auth.exception;

/**
 * Thrown when a sliding-window rate limit is exceeded. Carries the remaining
 * lockout duration so the HTTP layer can populate the {@code Retry-After} header
 * and the response body.
 */
public class RateLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitException(final long retryAfterSeconds) {
        super("Rate limit exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Remaining lockout in seconds (≥ 1). */
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
