package fr.pivot.auth.service;

import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.FeatureFlag;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.util.CryptoUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyBoolean;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenService}.
 * Verifies token issuance, validation, revocation and rotation logic.
 *
 * <p>Note: {@link FeatureFlagRepository#getInt} is a default interface method.
 * Mockito does not intercept default method calls — we stub {@code findByFlagKey}
 * instead to control the typed value returned by the default accessor.
 * When {@code findByFlagKey} returns empty, default accessors fall back to the
 * supplied default value.
 *
 * <p>LENIENT strictness: BeforeEach stubs (user.getId, user.getEmail) are not
 * consumed by every test — lenient avoids UnnecessaryStubbingException for these.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenServiceTest {

    @Mock private AccessTokenRepository tokenRepo;
    @Mock private FeatureFlagRepository flagRepo;
    @Mock private MeterRegistry meterRegistry;
    @Mock private Counter meterCounter;
    @Mock private User user;
    @Mock private TokenService self;

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(tokenRepo, flagRepo, meterRegistry, self, 60L);
        Mockito.lenient().when(user.getId()).thenReturn(42L);
        Mockito.lenient().when(user.getEmail()).thenReturn("test@pivot.app");
        // Mockito 5 does NOT delegate to default interface methods — wire them explicitly.
        // With findByFlagKey returning Optional.empty() (default mock), the real getInt()
        // falls back to the defaultValue parameter, mimicking the production fallback path.
        Mockito.lenient().when(flagRepo.getInt(anyString(), anyInt())).thenCallRealMethod();
        Mockito.lenient().when(flagRepo.getFloat(anyString(), anyDouble())).thenCallRealMethod();
        Mockito.lenient().when(flagRepo.getBool(anyString(), anyBoolean())).thenCallRealMethod();
        Mockito.lenient().when(flagRepo.isEnabled(anyString())).thenCallRealMethod();
        // Stub MeterRegistry counter to avoid NPE in validate() not-found path
        Mockito.lenient().when(meterRegistry.counter(anyString())).thenReturn(meterCounter);
        // Stub countByUserIdAndStatus so issue() does not hit NPE (0 active sessions = no eviction)
        Mockito.lenient().when(tokenRepo.countByUserIdAndStatus(any(), any())).thenReturn(0L);
    }

    // ----------------------------------------------------------------
    // issue()
    // ----------------------------------------------------------------

    @Test
    void issue_savesTokenWithHashedValue() {
        // findByFlagKey not stubbed → returns Optional.empty() by default → getInt falls back to 86400
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        final TokenService.TokenIssueResult result = tokenService.issue(
            user, "fp-123", "Chrome", "ua", "1.2.3.4", AuthMethod.PASSWORD, false);

        final ArgumentCaptor<AccessToken> captor = ArgumentCaptor.forClass(AccessToken.class);
        verify(tokenRepo).save(captor.capture());

        final AccessToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isNotEqualTo(result.rawToken());
        assertThat(saved.getTokenHash()).isEqualTo(CryptoUtils.sha256(result.rawToken()));
        assertThat(saved.getStatus()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(saved.getTtlSeconds()).isEqualTo(86400); // default
    }

    @Test
    void issue_usesRememberMeTtl_whenFlagTrue() {
        // Stub the flag to return 2592000 (30 days)
        when(flagRepo.findByFlagKey("SESSION_TTL_REMEMBER_ME_SECONDS"))
            .thenReturn(Optional.of(intFlag("2592000")));
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        final TokenService.TokenIssueResult result = tokenService.issue(
            user, null, null, "ua", "1.2.3.4", AuthMethod.PASSWORD, true);

        assertThat(result.ttlSeconds()).isEqualTo(2592000);
    }

    @Test
    void issue_rawTokenIsUnique_acrossMultipleCalls() {
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        final String t1 = tokenService.issue(user, null, null, "ua", "ip", AuthMethod.PASSWORD, false).rawToken();
        final String t2 = tokenService.issue(user, null, null, "ua", "ip", AuthMethod.PASSWORD, false).rawToken();

        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    void issue_setsCorrectAuthMethod() {
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        tokenService.issue(user, null, null, "ua", "ip", AuthMethod.GOOGLE, false);

        final ArgumentCaptor<AccessToken> captor = ArgumentCaptor.forClass(AccessToken.class);
        verify(tokenRepo).save(captor.capture());
        assertThat(captor.getValue().getAuthMethod()).isEqualTo(AuthMethod.GOOGLE);
    }

    @Test
    void issue_stripsHtmlFromDeviceName_beforeStorage() {
        // US02.2.3: the "device" field must be HTML-stripped before it ever reaches the DB.
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        tokenService.issue(user, "fp", "<script>evil()</script>Chrome", "ua", "ip", AuthMethod.PASSWORD, false);

        final ArgumentCaptor<AccessToken> captor = ArgumentCaptor.forClass(AccessToken.class);
        verify(tokenRepo).save(captor.capture());
        assertThat(captor.getValue().getDeviceName()).isEqualTo("evil()Chrome");
    }

    @Test
    void issue_truncatesDeviceNameTo200Chars_beforeStorage() {
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        final String longDeviceName = "a".repeat(250);

        tokenService.issue(user, "fp", longDeviceName, "ua", "ip", AuthMethod.PASSWORD, false);

        final ArgumentCaptor<AccessToken> captor = ArgumentCaptor.forClass(AccessToken.class);
        verify(tokenRepo).save(captor.capture());
        assertThat(captor.getValue().getDeviceName()).hasSize(200);
    }

    @Test
    void issue_toleratesNullDeviceName() {
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        tokenService.issue(user, null, null, "ua", "ip", AuthMethod.PASSWORD, false);

        final ArgumentCaptor<AccessToken> captor = ArgumentCaptor.forClass(AccessToken.class);
        verify(tokenRepo).save(captor.capture());
        assertThat(captor.getValue().getDeviceName()).isNull();
    }

    // ----------------------------------------------------------------
    // validate()
    // ----------------------------------------------------------------

    @Test
    void validate_returnsToken_whenActiveAndNotExpired() {
        final String raw = "test-raw-token";
        final AccessToken stored = validToken(raw, 3600);
        when(tokenRepo.findByTokenHashAndStatusWithUser(CryptoUtils.sha256(raw), TokenStatus.ACTIVE))
            .thenReturn(Optional.of(stored));

        final Optional<AccessToken> result = tokenService.validate(raw);

        assertThat(result).isPresent();
        // last_used_at is no longer written synchronously on the validate path.
        verify(tokenRepo, never()).save(stored);
    }

    @Test
    void validate_dispatchesAsyncLastUsedTouch_whenValid() {
        final String raw = "track-me";
        final AccessToken stored = validToken(raw, 3600);
        when(tokenRepo.findByTokenHashAndStatusWithUser(CryptoUtils.sha256(raw), TokenStatus.ACTIVE))
            .thenReturn(Optional.of(stored));

        tokenService.validate(raw);

        // Activity update is dispatched asynchronously via the self proxy (UPDATE by id).
        verify(self).touchLastUsed(any(), any());
    }

    @Test
    void validate_returnsEmpty_whenTokenNotFound() {
        when(tokenRepo.findByTokenHashAndStatusWithUser(any(), eq(TokenStatus.ACTIVE))).thenReturn(Optional.empty());
        assertThat(tokenService.validate("unknown-token")).isEmpty();
    }

    @Test
    void validate_returnsEmpty_forNullToken() {
        assertThat(tokenService.validate(null)).isEmpty();
        verify(tokenRepo, never()).findByTokenHashAndStatusWithUser(any(), any());
    }

    @Test
    void validate_returnsEmpty_forBlankToken() {
        assertThat(tokenService.validate("  ")).isEmpty();
        verify(tokenRepo, never()).findByTokenHashAndStatusWithUser(any(), any());
    }

    @Test
    void validate_marksExpired_andReturnsEmpty_whenTokenPastExpiry() {
        final String raw = "expired-raw";
        final AccessToken stored = new AccessToken();
        stored.setStatus(TokenStatus.ACTIVE);
        stored.setExpiresAt(Instant.now().minusSeconds(10));
        stored.setTtlSeconds(3600);
        stored.setTokenHash(CryptoUtils.sha256(raw));

        when(tokenRepo.findByTokenHashAndStatusWithUser(CryptoUtils.sha256(raw), TokenStatus.ACTIVE))
            .thenReturn(Optional.of(stored));
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        final Optional<AccessToken> result = tokenService.validate(raw);

        assertThat(result).isEmpty();
        assertThat(stored.getStatus()).isEqualTo(TokenStatus.EXPIRED);
    }

    // ----------------------------------------------------------------
    // rotate() — Blue Team fix: race condition handling
    // ----------------------------------------------------------------

    @Test
    void rotate_returnsEmpty_whenTokenAlreadyRevoked_raceConcurrentRequestFix() {
        final String raw = "already-rotated";
        final AccessToken existing = validToken(raw, 3600);

        // Simulate concurrent request already revoked the token
        when(tokenRepo.findByTokenHashAndStatusForUpdate(existing.getTokenHash(), TokenStatus.ACTIVE))
            .thenReturn(Optional.empty());

        final Optional<TokenService.TokenIssueResult> result = tokenService.rotate(existing);

        assertThat(result).isEmpty();
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void rotate_returnsNewToken_whenTokenStillActive() {
        final String raw = "active-raw";
        final AccessToken existing = validToken(raw, 3600);
        existing.setUser(user);
        existing.setAuthMethod(AuthMethod.PASSWORD);

        when(tokenRepo.findByTokenHashAndStatusForUpdate(existing.getTokenHash(), TokenStatus.ACTIVE))
            .thenReturn(Optional.of(existing));
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        final Optional<TokenService.TokenIssueResult> result = tokenService.rotate(existing);

        assertThat(result).isPresent();
        assertThat(result.get().rawToken()).isNotEqualTo(raw);
        // Grace window: the old token stays ACTIVE but is stamped rotated_at and its expiry is
        // shortened so in-flight concurrent requests still authenticate, then it dies shortly.
        assertThat(existing.getStatus()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(existing.getRotatedAt()).isNotNull();
        assertThat(existing.getExpiresAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(31));
    }

    @Test
    void rotate_doesNotEnforceMaxSessions() {
        final String raw = "no-evict-raw";
        final AccessToken existing = validToken(raw, 3600);
        existing.setUser(user);
        when(tokenRepo.findByTokenHashAndStatusForUpdate(existing.getTokenHash(), TokenStatus.ACTIVE))
            .thenReturn(Optional.of(existing));
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        tokenService.rotate(existing);

        // Rotation must not count active sessions nor evict the oldest — it replaces an existing
        // token (which stays ACTIVE during the grace window), so it consumes no extra slot.
        verify(tokenRepo, never()).countByUserIdAndStatus(any(), any());
        verify(tokenRepo, never()).findOldestActiveByUserId(any(), any(), any());
    }

    @Test
    void rotate_returnsEmpty_whenAlreadyRotated() {
        final String raw = "rotated-raw";
        final AccessToken existing = validToken(raw, 3600);
        existing.setUser(user);
        existing.setRotatedAt(Instant.now());

        when(tokenRepo.findByTokenHashAndStatusForUpdate(existing.getTokenHash(), TokenStatus.ACTIVE))
            .thenReturn(Optional.of(existing));

        final Optional<TokenService.TokenIssueResult> result = tokenService.rotate(existing);

        assertThat(result).isEmpty();
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void rotate_newToken_inheritsSameDeviceMetadata() {
        final String raw = "meta-raw";
        final AccessToken existing = validToken(raw, 3600);
        existing.setUser(user);
        existing.setAuthMethod(AuthMethod.GOOGLE);
        existing.setDeviceFingerprint("fp-xyz");
        existing.setRememberMe(true);

        when(tokenRepo.findByTokenHashAndStatusForUpdate(existing.getTokenHash(), TokenStatus.ACTIVE))
            .thenReturn(Optional.of(existing));
        when(flagRepo.findByFlagKey("SESSION_TTL_REMEMBER_ME_SECONDS"))
            .thenReturn(Optional.of(intFlag("2592000")));
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        tokenService.rotate(existing);

        final ArgumentCaptor<AccessToken> captor = ArgumentCaptor.forClass(AccessToken.class);
        // save called twice: once for revoke, once for new token
        verify(tokenRepo, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        final AccessToken newToken = captor.getAllValues().stream()
            .filter(t -> TokenStatus.ACTIVE.equals(t.getStatus()))
            .findFirst().orElseThrow();
        assertThat(newToken.getDeviceFingerprint()).isEqualTo("fp-xyz");
        assertThat(newToken.getAuthMethod()).isEqualTo(AuthMethod.GOOGLE);
        assertThat(newToken.isRememberMe()).isTrue();
    }

    // ----------------------------------------------------------------
    // revokeByRawToken()
    // ----------------------------------------------------------------

    @Test
    void revokeByRawToken_revokesToken_whenFound() {
        final String raw = "to-revoke";
        final AccessToken token = validToken(raw, 3600);
        when(tokenRepo.findByTokenHashAndStatus(CryptoUtils.sha256(raw), TokenStatus.ACTIVE))
            .thenReturn(Optional.of(token));
        when(tokenRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        tokenService.revokeByRawToken(raw);

        assertThat(token.getStatus()).isEqualTo(TokenStatus.REVOKED);
    }

    @Test
    void revokeByRawToken_isNoOp_whenNull() {
        tokenService.revokeByRawToken(null);
        verify(tokenRepo, never()).findByTokenHashAndStatus(any(), any());
    }

    @Test
    void revokeByRawToken_isNoOp_whenBlank() {
        tokenService.revokeByRawToken("   ");
        verify(tokenRepo, never()).findByTokenHashAndStatus(any(), any());
    }

    // ----------------------------------------------------------------
    // revokeAllForUser()
    // ----------------------------------------------------------------

    @Test
    void revokeAllForUser_callsRepository() {
        tokenService.revokeAllForUser(99L);
        verify(tokenRepo).revokeAllForUser(99L, TokenStatus.ACTIVE, TokenStatus.REVOKED);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private AccessToken validToken(final String rawToken, final int ttlSeconds) {
        final AccessToken token = new AccessToken();
        token.setStatus(TokenStatus.ACTIVE);
        token.setExpiresAt(Instant.now().plusSeconds(ttlSeconds));
        token.setTtlSeconds(ttlSeconds);
        token.setTokenHash(CryptoUtils.sha256(rawToken));
        return token;
    }

    private FeatureFlag intFlag(final String value) {
        final FeatureFlag f = new FeatureFlag();
        try {
            final var vField = FeatureFlag.class.getDeclaredField("value");
            vField.setAccessible(true);
            vField.set(f, value);
            final var tField = FeatureFlag.class.getDeclaredField("type");
            tField.setAccessible(true);
            tField.set(f, "int");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return f;
    }
}
