package fr.pivot.auth.entity;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AccessToken} domain logic.
 * Validates isValid() and needsRefresh() without Spring context.
 */
class AccessTokenTest {

    // ----------------------------------------------------------------
    // isValid()
    // ----------------------------------------------------------------

    @Test
    void isValid_returnsTrue_whenActiveAndNotExpired() {
        final AccessToken token = activeToken(Instant.now().plusSeconds(3600), 7200);
        assertThat(token.isValid()).isTrue();
    }

    @Test
    void isValid_returnsFalse_whenExpired() {
        final AccessToken token = activeToken(Instant.now().minusSeconds(1), 3600);
        assertThat(token.isValid()).isFalse();
    }

    @Test
    void isValid_returnsFalse_whenRevoked() {
        final AccessToken token = activeToken(Instant.now().plusSeconds(3600), 7200);
        token.setStatus(TokenStatus.REVOKED);
        assertThat(token.isValid()).isFalse();
    }

    @Test
    void isValid_returnsFalse_whenStatusExpired() {
        final AccessToken token = activeToken(Instant.now().plusSeconds(3600), 7200);
        token.setStatus(TokenStatus.EXPIRED);
        assertThat(token.isValid()).isFalse();
    }

    // ----------------------------------------------------------------
    // needsRefresh()
    // ----------------------------------------------------------------

    @Test
    void needsRefresh_returnsTrue_whenBelowThreshold() {
        // 100s remaining out of 3600s TTL = 2.7% → below 50% threshold
        final AccessToken token = activeToken(Instant.now().plusSeconds(100), 3600);
        assertThat(token.needsRefresh(0.5)).isTrue();
    }

    @Test
    void needsRefresh_returnsFalse_whenAboveThreshold() {
        // 2000s remaining out of 3600s TTL = 55% → above 50% threshold
        final AccessToken token = activeToken(Instant.now().plusSeconds(2000), 3600);
        assertThat(token.needsRefresh(0.5)).isFalse();
    }

    @Test
    void needsRefresh_returnsFalse_forExpiredToken_blueTeamFix() {
        // Expired token → remainingSeconds negative → must return false (not true)
        final AccessToken token = activeToken(Instant.now().minusSeconds(10), 3600);
        assertThat(token.needsRefresh(0.5)).isFalse();
    }

    @Test
    void needsRefresh_exactlyAtThreshold_returnsTrue() {
        // Exactly 50% remaining → 50% < 50% is false → returns false
        // But at 49.9% → returns true
        final AccessToken token = activeToken(Instant.now().plusSeconds(1799), 3600);
        // 1799/3600 = 49.97% < 50% → needs refresh
        assertThat(token.needsRefresh(0.5)).isTrue();
    }

    @Test
    void needsRefresh_respectsCustomThreshold_25percent() {
        // 30% remaining → above 25% → no refresh
        final AccessToken token = activeToken(Instant.now().plusSeconds(1080), 3600);
        assertThat(token.needsRefresh(0.25)).isFalse();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private AccessToken activeToken(final Instant expiresAt, final int ttlSeconds) {
        final AccessToken token = new AccessToken();
        token.setStatus(TokenStatus.ACTIVE);
        token.setExpiresAt(expiresAt);
        token.setTtlSeconds(ttlSeconds);
        return token;
    }
}
