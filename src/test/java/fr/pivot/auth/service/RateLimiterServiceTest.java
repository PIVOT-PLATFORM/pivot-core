package fr.pivot.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimiterService} — Redis sliding-window rate limiter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimiterServiceTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private RateLimiterService service;

    @BeforeEach
    void setUp() {
        service = new RateLimiterService(redis);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void isAllowed_returnsFalse_whenLockoutStillActive() {
        final long future = System.currentTimeMillis() / 1000 + 600;
        when(valueOps.get("rl:b:until")).thenReturn(String.valueOf(future));

        assertThat(service.isAllowed("b", 5, Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void isAllowed_returnsTrue_whenNoLockoutAndUnderLimit() {
        when(valueOps.get("rl:b:until")).thenReturn(null);
        when(valueOps.get("rl:b:count")).thenReturn("2");

        assertThat(service.isAllowed("b", 5, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void isAllowed_returnsTrue_whenCountKeyMissing() {
        when(valueOps.get("rl:b:until")).thenReturn(null);
        when(valueOps.get("rl:b:count")).thenReturn(null);

        assertThat(service.isAllowed("b", 5, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void isAllowed_setsLockoutAndReturnsFalse_whenLimitReached() {
        when(valueOps.get("rl:b:until")).thenReturn(null);
        when(valueOps.get("rl:b:count")).thenReturn("5");

        assertThat(service.isAllowed("b", 5, Duration.ofMinutes(10))).isFalse();
        verify(valueOps).set(eq("rl:b:until"), any(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void isAllowed_ignoresExpiredLockout() {
        final long past = System.currentTimeMillis() / 1000 - 10;
        when(valueOps.get("rl:b:until")).thenReturn(String.valueOf(past));
        when(valueOps.get("rl:b:count")).thenReturn("0");

        assertThat(service.isAllowed("b", 5, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void recordAttempt_setsExpiry_onFirstAttempt() {
        when(valueOps.increment("rl:b:count")).thenReturn(1L);

        service.recordAttempt("b", Duration.ofMinutes(10));

        verify(redis).expire("rl:b:count", 600L, TimeUnit.SECONDS);
    }

    @Test
    void recordAttempt_doesNotResetExpiry_onSubsequentAttempts() {
        when(valueOps.increment("rl:b:count")).thenReturn(3L);

        service.recordAttempt("b", Duration.ofMinutes(10));

        verify(redis, never()).expire(any(), anyLong(), any());
    }

    @Test
    void reset_deletesBothKeys() {
        service.reset("b");

        verify(redis).delete("rl:b:count");
        verify(redis).delete("rl:b:until");
    }

    @Test
    void checkAndRecord_recordsAndReturnsTrue_whenAllowed() {
        when(valueOps.get("rl:b:until")).thenReturn(null);
        when(valueOps.get("rl:b:count")).thenReturn("0");
        when(valueOps.increment("rl:b:count")).thenReturn(1L);

        assertThat(service.checkAndRecord("b", 5, Duration.ofMinutes(10))).isTrue();
        verify(valueOps).increment("rl:b:count");
    }

    @Test
    void checkAndRecord_returnsFalse_andDoesNotRecord_whenDenied() {
        final long future = System.currentTimeMillis() / 1000 + 600;
        when(valueOps.get("rl:b:until")).thenReturn(String.valueOf(future));

        assertThat(service.checkAndRecord("b", 5, Duration.ofMinutes(10))).isFalse();
        verify(valueOps, never()).increment(any(String.class));
    }

    @Test
    void bucketHelpers_produceExpectedKeys() {
        assertThat(service.loginIpBucket("1.2.3.4")).isEqualTo("login:ip:1.2.3.4");
        assertThat(service.loginEmailBucket("a@b.c")).isEqualTo("login:email:a@b.c");
        assertThat(service.registerIpBucket("1.2.3.4")).isEqualTo("register:ip:1.2.3.4");
        assertThat(service.forgotPasswordBucket("1.2.3.4")).isEqualTo("forgot:ip:1.2.3.4");
        assertThat(service.resetPasswordBucket("1.2.3.4")).isEqualTo("reset:ip:1.2.3.4");
        assertThat(service.verifyEmailBucket("1.2.3.4")).isEqualTo("verify-email:ip:1.2.3.4");
        assertThat(service.deviceOtpBucket("42")).isEqualTo("device-otp:user:42");
    }
}
