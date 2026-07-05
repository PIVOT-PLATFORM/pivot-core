package fr.pivot.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis sliding-window rate limiter (mirroring PHP RateLimiterService pattern).
 * Keys: rl:{bucket}:count and rl:{bucket}:until
 * Returns false when the request is denied, true when allowed.
 */
@Service
public class RateLimiterService {

    private static final String PREFIX = "rl:";
    private static final String SUFFIX_COUNT = ":count";
    private static final String SUFFIX_UNTIL = ":until";

    private final StringRedisTemplate redis;

    public RateLimiterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isAllowed(String bucket, int maxAttempts, Duration window) {
        String untilKey = PREFIX + bucket + SUFFIX_UNTIL;
        String until = redis.opsForValue().get(untilKey);
        if (until != null && Long.parseLong(until) > System.currentTimeMillis() / 1000) {
            return false;
        }

        String countKey = PREFIX + bucket + SUFFIX_COUNT;
        String countStr = redis.opsForValue().get(countKey);
        int count = countStr == null ? 0 : Integer.parseInt(countStr);

        if (count >= maxAttempts) {
            long lockoutUntil = System.currentTimeMillis() / 1000 + window.toSeconds();
            redis.opsForValue().set(untilKey, String.valueOf(lockoutUntil),
                window.toSeconds() + 60, TimeUnit.SECONDS);
            return false;
        }
        return true;
    }

    public void recordAttempt(String bucket, Duration window) {
        String countKey = PREFIX + bucket + SUFFIX_COUNT;
        Long current = redis.opsForValue().increment(countKey);
        if (current != null && current == 1) {
            redis.expire(countKey, window.toSeconds(), TimeUnit.SECONDS);
        }
    }

    public void reset(String bucket) {
        redis.delete(PREFIX + bucket + SUFFIX_COUNT);
        redis.delete(PREFIX + bucket + SUFFIX_UNTIL);
    }

    /**
     * Returns the remaining lockout duration in seconds for {@code bucket}, or 0 if not locked.
     */
    public long getRemainingSeconds(final String bucket) {
        String until = redis.opsForValue().get(PREFIX + bucket + SUFFIX_UNTIL);
        if (until == null) {
            return 0L;
        }
        long remaining = Long.parseLong(until) - System.currentTimeMillis() / 1000;
        return Math.max(0L, remaining);
    }

    /** Convenience: check + record in one call. Returns false if denied. */
    public boolean checkAndRecord(String bucket, int maxAttempts, Duration window) {
        if (!isAllowed(bucket, maxAttempts, window)) {
            return false;
        }
        recordAttempt(bucket, window);
        return true;
    }

    /** Buckets used across the auth flow */
    public String loginIpBucket(String ip) { return "login:ip:" + ip; }
    public String sessionRestoreBucket(String ip) { return "session-restore:ip:" + ip; }
    public String loginEmailBucket(String email) { return "login:email:" + email; }
    public String registerIpBucket(String ip) { return "register:ip:" + ip; }
    public String forgotPasswordBucket(String ip) { return "forgot:ip:" + ip; }
    public String resetPasswordBucket(String ip) { return "reset:ip:" + ip; }
    public String verifyEmailBucket(String ip) { return "verify-email:ip:" + ip; }
    public String resendVerificationBucket(String ip) { return "resend-verification:ip:" + ip; }
    public String deviceOtpBucket(String userId) { return "device-otp:user:" + userId; }
    public String contactIpBucket(String ip) { return "contact:ip:" + ip; }
    public String changePasswordUserBucket(String userId) { return "change-password:user:" + userId; }
    public String changePasswordIpBucket(String ip) { return "change-password:ip:" + ip; }
}
