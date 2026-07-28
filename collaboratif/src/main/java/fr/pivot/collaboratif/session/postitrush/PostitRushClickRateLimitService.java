package fr.pivot.collaboratif.session.postitrush;

import fr.pivot.collaboratif.exception.TooManyRequestsException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed rate limiter for {@code POST .../postit-rush/click} (US47.2.1 security AC:
 * "Rate-limit click submissions per participantId (429 beyond plausible human input) to prevent
 * automated point farming").
 *
 * <p>Same fixed-window counter shape as {@code JoinRateLimitService} — a 1-second window per
 * participant, capped at {@link PostitRushConstants#CLICK_RATE_LIMIT_PER_SECOND}. The check and
 * increment are not atomic at the Redis level; the slight over-admission possible under extreme
 * races is an accepted tradeoff here too (protecting fair-play, not a financial transaction).
 */
@Service
public class PostitRushClickRateLimitService {

    private static final Duration WINDOW = Duration.ofSeconds(1);
    private static final String PREFIX = "rate:postit-rush:click:";

    private final StringRedisTemplate redis;

    /**
     * Creates the rate limiter backed by the shared Redis instance.
     *
     * @param redis the string Redis template
     */
    public PostitRushClickRateLimitService(final StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Increments the per-participant click counter, throwing {@link TooManyRequestsException} if
     * it exceeds the plausible-human-input limit within the current 1-second window.
     *
     * @param participantId the clicking participant's id
     * @throws TooManyRequestsException if the limit is exceeded
     */
    public void checkAndIncrement(final UUID participantId) {
        String key = PREFIX + participantId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        if (count != null && count > PostitRushConstants.CLICK_RATE_LIMIT_PER_SECOND) {
            throw new TooManyRequestsException(
                    "Rate limit exceeded for participant " + participantId + ": max "
                            + PostitRushConstants.CLICK_RATE_LIMIT_PER_SECOND + " clicks per second");
        }
    }
}
