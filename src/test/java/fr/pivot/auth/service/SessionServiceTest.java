package fr.pivot.auth.service;

import fr.pivot.auth.dto.DeviceOtpRequest;
import fr.pivot.auth.dto.LoginRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.DeviceVerifyToken;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.DeviceVerifyTokenRepository;
import fr.pivot.auth.repository.FeatureFlagRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionService} — login, device-OTP verification,
 * session restore and logout flows with rate limiting and MFA branches.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private TenantRepository tenantRepo;
    @Mock private FeatureFlagRepository featureFlagRepo;
    @Mock private DeviceVerifyTokenRepository deviceVerifyRepo;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenService tokenService;
    @Mock private EmailService emailService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private TrustedDeviceService trustedDeviceService;
    @Mock private AuditService auditService;
    @Mock private User user;
    @Mock private Tenant tenant;

    private static final String OTP_SECRET = "test-otp-secret";

    private SessionService service;

    @BeforeEach
    void setUp() {
        when(featureFlagRepo.getInt("DEVICE_VERIFY_TTL_MINUTES", 15)).thenReturn(15);
        service = new SessionService(userRepo, tenantRepo, featureFlagRepo, deviceVerifyRepo,
            passwordEncoder, tokenService, emailService, rateLimiter, trustedDeviceService,
            auditService, OTP_SECRET);

        when(rateLimiter.loginIpBucket(anyString())).thenReturn("login:ip");
        when(rateLimiter.loginEmailBucket(anyString())).thenReturn("login:email");
        when(rateLimiter.deviceOtpBucket(anyString())).thenReturn("device-otp");
        when(rateLimiter.sessionRestoreBucket(anyString())).thenReturn("session-restore:ip");
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(true);
        when(tenantRepo.findBySlug("pivot-saas")).thenReturn(Optional.of(tenant));
        when(tenant.getId()).thenReturn(1L);
        when(tenant.getSlug()).thenReturn("pivot-saas");

        when(user.getId()).thenReturn(7L);
        when(user.getEmail()).thenReturn("user@x.com");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getLastName()).thenReturn("Doe");
        when(user.getRole()).thenReturn("ROLE_USER");
        when(user.getPasswordHash()).thenReturn("hash");
        when(user.isActive()).thenReturn(true);
        when(user.isBlocked()).thenReturn(false);
        when(user.isEmailVerified()).thenReturn(true);
        when(user.getTenant()).thenReturn(tenant);

        when(tokenService.issue(any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new TokenService.TokenIssueResult("raw-token", Instant.now().plusSeconds(3600), 3600));
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    private LoginRequest loginReq(final String fingerprint) {
        return new LoginRequest("User@X.com", "pw", fingerprint, "Chrome", false);
    }

    // ---------------- login ----------------

    @Test
    void login_throws429_whenRateLimited() {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(false);

        final LoginRequest lr = loginReq(null);
        assertThatThrownBy(() -> service.login(lr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void login_throws401_andRecordsAttempt_whenUserUnknown() {
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.empty());

        final LoginRequest lr = loginReq(null);
        assertThatThrownBy(() -> service.login(lr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(rateLimiter).recordAttempt(eq("login:ip"), any());
        verify(auditService).log(null, AuditService.LOGIN_FAILED, "ip", "ua");
    }

    @Test
    void login_throws401_whenPasswordWrong() {
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(false);

        final LoginRequest lr = loginReq(null);
        assertThatThrownBy(() -> service.login(lr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_throws403_whenAccountInactive() {
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(user.isActive()).thenReturn(false);

        final LoginRequest lr = loginReq(null);
        assertThatThrownBy(() -> service.login(lr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void login_throws403_whenEmailNotVerified() {
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(user.isEmailVerified()).thenReturn(false);

        final LoginRequest lr = loginReq(null);
        assertThatThrownBy(() -> service.login(lr, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void login_returnsSuccess_whenNoFingerprint() {
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

        final LoginResult result = service.login(loginReq(null), "ip", "ua");

        assertThat(result.requiresDeviceVerification()).isFalse();
        assertThat(result.sessionToken()).isEqualTo("raw-token");
        verify(userRepo).updateLastLoginAt(7L);
        verify(auditService).log(user, AuditService.LOGIN, "ip", "ua");
    }

    @Test
    void login_requiresDeviceVerification_whenUntrustedAndSuperAdmin() {
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(user.getRole()).thenReturn("ROLE_SUPER_ADMIN");
        when(trustedDeviceService.isTrusted(user, "fp")).thenReturn(false);
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(true);

        final LoginResult result = service.login(loginReq("fp"), "ip", "ua");

        assertThat(result.requiresDeviceVerification()).isTrue();
        assertThat(result.pendingDeviceFingerprint()).isEqualTo("fp");
        verify(deviceVerifyRepo).save(any(DeviceVerifyToken.class));
        verify(emailService).sendDeviceVerifyEmail(eq("user@x.com"), eq("Alice"), anyString(), eq("Chrome"), any(Locale.class));
    }

    @Test
    void login_returnsSuccess_whenDeviceTrusted() {
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(trustedDeviceService.isTrusted(user, "fp")).thenReturn(true);

        final LoginResult result = service.login(loginReq("fp"), "ip", "ua");

        assertThat(result.requiresDeviceVerification()).isFalse();
        verify(tokenService).issue(eq(user), eq("fp"), eq("Chrome"), eq("ua"), eq("ip"), eq(AuthMethod.PASSWORD), eq(false));
    }

    // ---------------- verifyDeviceOtp ----------------

    private DeviceVerifyToken pendingToken(final String otpHash) {
        final DeviceVerifyToken dvt = new DeviceVerifyToken();
        dvt.setUser(user);
        dvt.setOtpHash(otpHash);
        return dvt;
    }

    @Test
    void verifyDeviceOtp_throws400_whenNoPendingSession() {
        when(deviceVerifyRepo.findPendingByFingerprint(eq("fp"), any(Instant.class))).thenReturn(Optional.empty());

        final DeviceOtpRequest otpReq = new DeviceOtpRequest("fp", "123456", "Chrome", false);
        assertThatThrownBy(() -> service.verifyDeviceOtp(otpReq, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyDeviceOtp_throws429_whenRateLimited() {
        when(deviceVerifyRepo.findPendingByFingerprint(eq("fp"), any(Instant.class)))
            .thenReturn(Optional.of(pendingToken(CryptoUtils.hmacSha256("123456", OTP_SECRET))));
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(false);

        final DeviceOtpRequest otpReq = new DeviceOtpRequest("fp", "123456", "Chrome", false);
        assertThatThrownBy(() -> service.verifyDeviceOtp(otpReq, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void verifyDeviceOtp_throws401_andIncrementsAttempts_whenOtpWrong() {
        final DeviceVerifyToken dvt = pendingToken(CryptoUtils.hmacSha256("000000", OTP_SECRET));
        when(deviceVerifyRepo.findPendingByFingerprint(eq("fp"), any(Instant.class))).thenReturn(Optional.of(dvt));
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(true);

        final DeviceOtpRequest otpReq = new DeviceOtpRequest("fp", "123456", "Chrome", false);
        assertThatThrownBy(() -> service.verifyDeviceOtp(otpReq, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(dvt.getAttempts()).isEqualTo(1);
        verify(deviceVerifyRepo).save(dvt);
    }

    @Test
    void verifyDeviceOtp_returnsSuccess_whenOtpValid() {
        final DeviceVerifyToken dvt = pendingToken(CryptoUtils.hmacSha256("123456", OTP_SECRET));
        when(deviceVerifyRepo.findPendingByFingerprint(eq("fp"), any(Instant.class))).thenReturn(Optional.of(dvt));
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(true);

        final LoginResult result = service.verifyDeviceOtp(new DeviceOtpRequest("fp", "123456", "Chrome", true), "ip", "ua");

        assertThat(result.sessionToken()).isEqualTo("raw-token");
        assertThat(dvt.getConfirmedAt()).isNotNull();
        verify(trustedDeviceService).trust(user, "fp", "Chrome");
        verify(auditService).log(user, AuditService.DEVICE_VERIFIED, "ip", "ua");
    }

    // ---------------- restoreSession ----------------

    @Test
    void restoreSession_throws429_whenRateLimited() {
        when(rateLimiter.isAllowed(anyString(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.restoreSession("cookie", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void restoreSession_throws401_whenTokenInvalid() {
        when(tokenService.validate("cookie")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restoreSession("cookie", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void restoreSession_throws403_whenAccountInactive() {
        final AccessToken token = new AccessToken();
        token.setUser(user);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setTtlSeconds(3600);
        when(tokenService.validate("cookie")).thenReturn(Optional.of(token));
        when(user.isActive()).thenReturn(false);

        assertThatThrownBy(() -> service.restoreSession("cookie", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void restoreSession_returnsSuccess_whenTokenValid() {
        final AccessToken token = new AccessToken();
        token.setUser(user);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setTtlSeconds(3600);
        when(tokenService.validate("cookie")).thenReturn(Optional.of(token));

        final LoginResult result = service.restoreSession("cookie", "ip", "ua");

        assertThat(result.sessionToken()).isEqualTo("cookie");
        assertThat(result.requiresDeviceVerification()).isFalse();
    }

    // ---------------- logout ----------------

    @Test
    void logout_revokesToken() {
        service.logout("cookie");
        verify(tokenService).revokeByRawToken("cookie");
    }

    @Test
    void logout_isSilent_whenTokenNull() {
        service.logout(null);
        verify(tokenService).revokeByRawToken(null);
    }

    private static <T> T eq(final T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
