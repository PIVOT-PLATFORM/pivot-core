package fr.pivot.auth.service;

import fr.pivot.auth.dto.ForgotPasswordRequest;
import fr.pivot.auth.dto.ResetPasswordRequest;
import fr.pivot.auth.entity.PasswordResetToken;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.PasswordResetTokenRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.util.CryptoUtils;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PasswordService} — forgot/reset password flows,
 * including no-enumeration silence and security-event session revocation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private TenantRepository tenantRepo;
    @Mock private PasswordResetTokenRepository passwordResetRepo;
    @Mock private FeatureFlagRepository featureFlagRepo;
    @Mock private TokenService tokenService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private SecurityNotificationService securityNotificationService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private AuditService auditService;
    @Mock private User user;
    @Mock private Tenant tenant;

    private PasswordService service;

    @BeforeEach
    void setUp() {
        when(featureFlagRepo.getInt("PASSWORD_RESET_TTL_MINUTES", 15)).thenReturn(15);
        service = new PasswordService(userRepo, tenantRepo, passwordResetRepo, featureFlagRepo,
            tokenService, passwordEncoder, emailService, securityNotificationService, rateLimiter, auditService);
        when(rateLimiter.forgotPasswordBucket(anyString())).thenReturn("forgot:ip:ip");
        when(rateLimiter.resetPasswordBucket(anyString())).thenReturn("reset:ip:ip");
        when(tenantRepo.findBySlug("pivot-saas")).thenReturn(Optional.of(tenant));
        when(tenant.getId()).thenReturn(1L);
        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("user@x.com");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.isEmailVerified()).thenReturn(true);
        when(user.isActive()).thenReturn(true);
    }

    // ---------------- forgotPassword ----------------

    @Test
    void forgotPassword_isSilent_whenRateLimited() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(false);

        service.forgotPassword(new ForgotPasswordRequest("Test@X.com"), "ip", "ua");

        verify(passwordResetRepo, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any(), any(), any());
    }

    @Test
    void forgotPassword_isSilent_whenEmailUnknown() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "test@x.com"))
            .thenReturn(Optional.empty());

        service.forgotPassword(new ForgotPasswordRequest("Test@X.com"), "ip", "ua");

        verify(passwordResetRepo, never()).save(any());
    }

    @Test
    void forgotPassword_skips_whenUserNotVerified() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "test@x.com"))
            .thenReturn(Optional.of(user));
        when(user.isEmailVerified()).thenReturn(false);

        service.forgotPassword(new ForgotPasswordRequest("Test@X.com"), "ip", "ua");

        verify(passwordResetRepo, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(any(), any(), any(), any());
    }

    @Test
    void forgotPassword_sendsResetEmail_onHappyPath() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "test@x.com"))
            .thenReturn(Optional.of(user));

        service.forgotPassword(new ForgotPasswordRequest("Test@X.com"), "ip", "ua");

        verify(passwordResetRepo).save(any(PasswordResetToken.class));
        verify(emailService).sendPasswordResetEmail(eq("user@x.com"), eq("Alice"), anyString(), any(Locale.class));
        verify(auditService).log(user, AuditService.PASSWORD_RESET_REQUEST, "ip", "ua");
    }

    @Test
    void forgotPassword_throws500_whenDefaultTenantMissing() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(tenantRepo.findBySlug("pivot-saas")).thenReturn(Optional.empty());

        final ForgotPasswordRequest fr = new ForgotPasswordRequest("a@b.c");
        assertThatThrownBy(() -> service.forgotPassword(fr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ---------------- resetPassword ----------------

    @Test
    void resetPassword_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(false);

        final ResetPasswordRequest rr = new ResetPasswordRequest("t", "password1");
        assertThatThrownBy(() -> service.resetPassword(rr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void resetPassword_throws400_whenTokenInvalid() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(passwordResetRepo.findByTokenHashAndUsedAtIsNull(anyString())).thenReturn(Optional.empty());

        final ResetPasswordRequest rr = new ResetPasswordRequest("bad", "password1");
        assertThatThrownBy(() -> service.resetPassword(rr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resetPassword_throws400_whenTokenExpired() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        final PasswordResetToken prt = new PasswordResetToken();
        prt.setUser(user);
        prt.setExpiresAt(Instant.now().minusSeconds(10));
        when(passwordResetRepo.findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256("expired")))
            .thenReturn(Optional.of(prt));

        final ResetPasswordRequest rr = new ResetPasswordRequest("expired", "password1");
        assertThatThrownBy(() -> service.resetPassword(rr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resetPassword_updatesPasswordAndRevokesSessions_onHappyPath() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        final PasswordResetToken prt = new PasswordResetToken();
        prt.setUser(user);
        prt.setExpiresAt(Instant.now().plusSeconds(600));
        when(passwordResetRepo.findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256("valid")))
            .thenReturn(Optional.of(prt));
        when(passwordResetRepo.markUsed(any(), any())).thenReturn(1);
        when(passwordEncoder.encode("password1")).thenReturn("hashed");

        service.resetPassword(new ResetPasswordRequest("valid", "password1"), "ip", "ua");

        verify(user).setPasswordHash("hashed");
        verify(userRepo).save(user);
        verify(tokenService).revokeAllForUser(7L);
        verify(securityNotificationService).notifyPasswordChanged(eq(user), any(Instant.class), eq("ip"));
        verify(auditService).log(user, AuditService.PASSWORD_RESET, "ip", "ua");
    }

    // ---------------- checkResetToken ----------------

    @Test
    void checkResetToken_throws400_whenTokenNotFound() {
        when(passwordResetRepo.findByTokenHashAndUsedAtIsNull(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkResetToken("missing"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkResetToken_throws400_whenTokenExpired() {
        final PasswordResetToken prt = new PasswordResetToken();
        prt.setUser(user);
        prt.setExpiresAt(Instant.now().minusSeconds(10));
        when(passwordResetRepo.findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256("expired")))
            .thenReturn(Optional.of(prt));

        assertThatThrownBy(() -> service.checkResetToken("expired"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkResetToken_succeeds_whenTokenValid() {
        final PasswordResetToken prt = new PasswordResetToken();
        prt.setUser(user);
        prt.setExpiresAt(Instant.now().plusSeconds(600));
        when(passwordResetRepo.findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256("valid")))
            .thenReturn(Optional.of(prt));

        service.checkResetToken("valid"); // no exception
    }
}
