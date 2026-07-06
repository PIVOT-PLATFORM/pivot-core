package fr.pivot.auth.service;

import fr.pivot.auth.entity.SuspiciousLoginToken;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.SuspiciousLoginTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SuspiciousLoginService} — passive "unknown device" alert (US01.4.3a)
 * and its "Not me" full re-authentication confirmation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SuspiciousLoginServiceTest {

    @Mock private SuspiciousLoginTokenRepository tokenRepo;
    @Mock private FeatureFlagRepository featureFlagRepo;
    @Mock private EmailService emailService;
    @Mock private AuditService auditService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenService tokenService;
    @Mock private TrustedDeviceService trustedDeviceService;
    @Mock private User user;

    private SuspiciousLoginService service;

    @BeforeEach
    void setUp() {
        service = new SuspiciousLoginService(tokenRepo, featureFlagRepo, emailService, auditService,
            rateLimiter, passwordEncoder, tokenService, trustedDeviceService);

        when(featureFlagRepo.getInt("SUSPICIOUS_LOGIN_OTP_TTL_MINUTES", 60)).thenReturn(60);
        when(rateLimiter.suspiciousLoginAlertBucket(anyString())).thenReturn("suspicious-login-alert:user");
        when(rateLimiter.suspiciousLoginConfirmIpBucket(anyString())).thenReturn("suspicious-login-confirm:ip");
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(true);

        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("user@x.com");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getPasswordHash()).thenReturn("hash");
        when(user.getLocale()).thenReturn("fr");
    }

    // ---------------- alertIfUnknownDevice ----------------

    @Test
    void alertIfUnknownDevice_doesNothing_whenDeviceAlreadyTrusted() {
        service.alertIfUnknownDevice(user, true, "fp", "Chrome", "ip", "ua");

        verify(auditService, never()).log(any(User.class), anyString(), anyString(), anyString());
        verify(tokenRepo, never()).save(any());
        verify(emailService, never())
            .sendSuspiciousLoginAlertEmail(anyString(), anyString(), anyString(), any(Instant.class), anyString(), any(Locale.class));
    }

    @Test
    void alertIfUnknownDevice_sendsAlertAndAudits_whenDeviceUnknown() {
        service.alertIfUnknownDevice(user, false, "fp", "Chrome", "ip", "ua");

        verify(auditService).log(user, AuditService.SUSPICIOUS_LOGIN_DETECTED, "ip", "ua");
        verify(tokenRepo).save(any(SuspiciousLoginToken.class));
        verify(emailService).sendSuspiciousLoginAlertEmail(
            eq("user@x.com"), eq("Alice"), eq("Chrome"), any(Instant.class), anyString(), any(Locale.class));
    }

    @Test
    void alertIfUnknownDevice_persistsTokenWithDeviceAndIpMetadata() {
        service.alertIfUnknownDevice(user, false, "fp", "Chrome", "1.2.3.4", "ua");

        final var captor = org.mockito.ArgumentCaptor.forClass(SuspiciousLoginToken.class);
        verify(tokenRepo).save(captor.capture());
        final SuspiciousLoginToken saved = captor.getValue();
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getDeviceFingerprint()).isEqualTo("fp");
        assertThat(saved.getDeviceName()).isEqualTo("Chrome");
        assertThat(saved.getIpAddress()).isEqualTo("1.2.3.4");
        assertThat(saved.getTokenHash()).isNotBlank();
        assertThat(saved.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void alertIfUnknownDevice_stillAudits_butSkipsEmail_whenRateLimited() {
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(false);

        service.alertIfUnknownDevice(user, false, "fp", "Chrome", "ip", "ua");

        verify(auditService).log(user, AuditService.SUSPICIOUS_LOGIN_DETECTED, "ip", "ua");
        verify(tokenRepo, never()).save(any());
        verify(emailService, never())
            .sendSuspiciousLoginAlertEmail(anyString(), anyString(), anyString(), any(Instant.class), anyString(), any(Locale.class));
    }

    // ---------------- confirmNotMe ----------------

    private SuspiciousLoginToken pendingToken(final String tokenHash, final String fingerprint) {
        final SuspiciousLoginToken token = new SuspiciousLoginToken();
        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setDeviceFingerprint(fingerprint);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        return token;
    }

    @Test
    void confirmNotMe_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.confirmNotMe("raw", "pw", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void confirmNotMe_throws400_whenTokenNotFound() {
        when(tokenRepo.findByTokenHashAndUsedAtIsNull(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmNotMe("raw", "pw", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void confirmNotMe_throws400_whenTokenExpired() {
        final SuspiciousLoginToken token = pendingToken("hash", "fp");
        token.setExpiresAt(Instant.now().minusSeconds(10));
        when(tokenRepo.findByTokenHashAndUsedAtIsNull(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.confirmNotMe("raw", "pw", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(tokenRepo, never()).markUsed(any(), any());
    }

    @Test
    void confirmNotMe_throws401_andAudits_whenPasswordWrong() {
        final SuspiciousLoginToken token = pendingToken("hash", "fp");
        when(tokenRepo.findByTokenHashAndUsedAtIsNull(anyString())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.confirmNotMe("raw", "wrong", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(auditService).log(user, AuditService.SUSPICIOUS_LOGIN_NOT_ME_FAILED, "ip", "ua");
        verify(tokenRepo, never()).markUsed(any(), any());
        verify(tokenService, never()).revokeAllForUser(any());
        verify(trustedDeviceService, never()).revoke(any(), any());
    }

    @Test
    void confirmNotMe_throws400_whenTokenRaceLostToConcurrentRequest() {
        final SuspiciousLoginToken token = pendingToken("hash", "fp");
        when(tokenRepo.findByTokenHashAndUsedAtIsNull(anyString())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(tokenRepo.markUsed(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirmNotMe("raw", "pw", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(tokenService, never()).revokeAllForUser(any());
    }

    @Test
    void confirmNotMe_revokesDeviceAndAllSessions_andAudits_onSuccess() {
        final SuspiciousLoginToken token = pendingToken("hash", "fp");
        when(tokenRepo.findByTokenHashAndUsedAtIsNull(anyString())).thenReturn(Optional.of(token));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(tokenRepo.markUsed(any(), any())).thenReturn(1);

        service.confirmNotMe("raw", "pw", "ip", "ua");

        verify(trustedDeviceService).revoke(user, "fp");
        verify(tokenService).revokeAllForUser(7L);
        verify(auditService).log(user, AuditService.SUSPICIOUS_LOGIN_NOT_ME_CONFIRMED, "ip", "ua");
    }
}
