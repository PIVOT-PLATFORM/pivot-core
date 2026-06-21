package fr.pivot.auth.service;

import fr.pivot.auth.dto.DeviceOtpRequest;
import fr.pivot.auth.dto.LoginRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.DeviceVerifyToken;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.mapper.UserMapper;
import fr.pivot.auth.repository.DeviceVerifyTokenRepository;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Handles login, device-OTP verification, session restore and logout.
 *
 * <p>Single responsibility: active session lifecycle — from credential validation to
 * opaque token issuance, validation and revocation (US-AUTH-002).
 *
 * <p>JWT and refresh tokens have been removed. Authentication state is backed by the
 * {@code access_tokens} table via {@link TokenService}. TTL and auto-refresh threshold
 * are admin-configurable via {@code feature_flags}.
 *
 * <p>Device MFA is driven by {@code MFA_NEW_DEVICE_OTP} feature flag.
 *
 * @see PasswordService for password reset flows
 * @see RegistrationService for account creation and email verification
 * @see TokenService for opaque token lifecycle
 */
@Service
public class SessionService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);

    private static final int LOGIN_MAX_PER_IP = 20;
    private static final int LOGIN_MAX_PER_EMAIL = 10;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final int DEVICE_OTP_MAX = 5;
    private static final Duration DEVICE_OTP_WINDOW = Duration.ofMinutes(15);
    private static final int SESSION_RESTORE_MAX = 30;
    private static final Duration SESSION_RESTORE_WINDOW = Duration.ofMinutes(5);

    private final UserRepository userRepo;
    private final TenantRepository tenantRepo;
    private final FeatureFlagRepository featureFlagRepo;
    private final DeviceVerifyTokenRepository deviceVerifyRepo;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final EmailService emailService;
    private final RateLimiterService rateLimiter;
    private final TrustedDeviceService trustedDeviceService;
    private final AuditService auditService;

    private final long deviceVerifyTtlMinutes;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Constructs the service with its required collaborators.
     *
     * @param userRepo               JPA repository for users
     * @param tenantRepo             JPA repository for tenants
     * @param featureFlagRepo        JPA repository for feature flags (MFA toggles)
     * @param deviceVerifyRepo       JPA repository for device OTP tokens
     * @param passwordEncoder        BCrypt encoder for credential verification
     * @param tokenService           opaque session token lifecycle manager
     * @param emailService           transactional email sender
     * @param rateLimiter            sliding-window rate limiter backed by Redis
     * @param trustedDeviceService   manages trusted device records
     * @param auditService           async audit event logger
     * @param deviceVerifyTtlMinutes OTP token TTL in minutes
     */
    public SessionService(
            final UserRepository userRepo,
            final TenantRepository tenantRepo,
            final FeatureFlagRepository featureFlagRepo,
            final DeviceVerifyTokenRepository deviceVerifyRepo,
            final PasswordEncoder passwordEncoder,
            final TokenService tokenService,
            final EmailService emailService,
            final RateLimiterService rateLimiter,
            final TrustedDeviceService trustedDeviceService,
            final AuditService auditService,
            @Value("${pivot.auth.device-verify-ttl-minutes:15}") final long deviceVerifyTtlMinutes) {
        this.userRepo = userRepo;
        this.tenantRepo = tenantRepo;
        this.featureFlagRepo = featureFlagRepo;
        this.deviceVerifyRepo = deviceVerifyRepo;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.trustedDeviceService = trustedDeviceService;
        this.auditService = auditService;
        this.deviceVerifyTtlMinutes = deviceVerifyTtlMinutes;
    }

    /**
     * Authenticates a user by email and password.
     *
     * <p>If device MFA is enabled and the device fingerprint is unknown, issues a
     * device OTP and returns {@link LoginResult#requiresDeviceVerification(String)}.
     * Otherwise, issues an opaque session token via {@link TokenService}.
     *
     * @param req       login payload (email, password, optional device fingerprint/name, rememberMe)
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit and device metadata
     * @return {@link LoginResult} — either device verification pending or session token issued
     * @throws ResponseStatusException 401 on invalid credentials, 403 on blocked/inactive
     *     account or unverified email, 429 on rate limit
     */
    @Transactional
    public LoginResult login(final LoginRequest req, final String ip, final String userAgent) {
        if (!rateLimiter.isAllowed(rateLimiter.loginIpBucket(ip), LOGIN_MAX_PER_IP, LOGIN_WINDOW)
                || !rateLimiter.isAllowed(rateLimiter.loginEmailBucket(req.email()), LOGIN_MAX_PER_EMAIL, LOGIN_WINDOW)) {
            LOG.warn("event=LOGIN_RATE_LIMITED ip={} email={}", ip, req.email());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final Tenant tenant = saasDefaultTenant();
        final User user = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(tenant.getId(), req.email().toLowerCase())
            .orElse(null);

        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            rateLimiter.recordAttempt(rateLimiter.loginIpBucket(ip), LOGIN_WINDOW);
            rateLimiter.recordAttempt(rateLimiter.loginEmailBucket(req.email()), LOGIN_WINDOW);
            auditService.log(user, AuditService.LOGIN_FAILED, ip, userAgent);
            LOG.warn("event=AUTH_FAILED reason=bad_credentials ip={}", ip);
            // Same error for unknown email vs wrong password — no enumeration (RGPD)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Identifiants invalides");
        }

        if (!user.isActive() || user.isBlocked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Compte désactivé");
        }

        if (!user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email non vérifié");
        }

        rateLimiter.reset(rateLimiter.loginEmailBucket(req.email()));

        final String fingerprint = req.deviceFingerprint();
        final boolean mfaNewDevice = featureFlagRepo.isEnabled("MFA_NEW_DEVICE_OTP");
        final boolean isSuperAdmin = "ROLE_SUPER_ADMIN".equals(user.getRole());

        if (fingerprint != null && !fingerprint.isBlank()) {
            final boolean trusted = trustedDeviceService.isTrusted(user, fingerprint);
            if (!trusted && (mfaNewDevice || isSuperAdmin)) {
                sendDeviceOtp(user, fingerprint, req.deviceName(), ip, userAgent);
                return LoginResult.requiresDeviceVerification(fingerprint);
            }
        }

        userRepo.updateLastLoginAt(user.getId());
        final boolean rememberMe = Boolean.TRUE.equals(req.rememberMe());
        final TokenService.TokenIssueResult issued = tokenService.issue(
            user, fingerprint, req.deviceName(), userAgent, ip, AuthMethod.PASSWORD, rememberMe);

        auditService.log(user, AuditService.LOGIN, ip, userAgent);
        LOG.info("event=LOGIN_SUCCESS userId={} rememberMe={} ttl={}s", user.getId(), rememberMe, issued.ttlSeconds());
        return LoginResult.success(
            issued.rawToken(),
            issued.expiresAt().toEpochMilli(),
            issued.ttlSeconds(),
            UserMapper.toUserInfo(user));
    }

    /**
     * Validates a device OTP and completes the login flow.
     *
     * <p>On success, marks the device as trusted, issues an opaque session token,
     * and resets the OTP rate limit.
     *
     * @param req       OTP payload (device fingerprint, 6-digit OTP, optional device name, rememberMe)
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit
     * @return {@link LoginResult} with issued session token
     * @throws ResponseStatusException 400 if no pending OTP session, 401 on wrong OTP,
     *     429 on rate limit
     */
    @Transactional
    public LoginResult verifyDeviceOtp(final DeviceOtpRequest req, final String ip, final String userAgent) {
        final DeviceVerifyToken dvt = deviceVerifyRepo
            .findPendingByFingerprint(req.deviceFingerprint(), Instant.now())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session de vérification introuvable"));

        if (!rateLimiter.checkAndRecord(
                rateLimiter.deviceOtpBucket(dvt.getUser().getId().toString()),
                DEVICE_OTP_MAX, DEVICE_OTP_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        if (!dvt.getOtpHash().equals(fr.pivot.auth.util.CryptoUtils.sha256(req.otp()))) {
            dvt.setAttempts(dvt.getAttempts() + 1);
            deviceVerifyRepo.save(dvt);
            auditService.log(dvt.getUser(), AuditService.DEVICE_OTP_FAILED, ip, userAgent);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OTP invalide");
        }

        dvt.setConfirmedAt(Instant.now());
        deviceVerifyRepo.save(dvt);

        final User user = dvt.getUser();
        trustedDeviceService.trust(user, req.deviceFingerprint(), req.deviceName());
        userRepo.updateLastLoginAt(user.getId());
        rateLimiter.reset(rateLimiter.deviceOtpBucket(user.getId().toString()));

        final boolean rememberMe = Boolean.TRUE.equals(req.rememberMe());
        final TokenService.TokenIssueResult issued = tokenService.issue(
            user, req.deviceFingerprint(), req.deviceName(), userAgent, ip, AuthMethod.PASSWORD, rememberMe);

        auditService.log(user, AuditService.DEVICE_VERIFIED, ip, userAgent);
        LOG.info("event=DEVICE_OTP_VERIFIED userId={} rememberMe={}", user.getId(), rememberMe);
        return LoginResult.success(
            issued.rawToken(),
            issued.expiresAt().toEpochMilli(),
            issued.ttlSeconds(),
            UserMapper.toUserInfo(user));
    }

    /**
     * Restores a session from the HTTP-only session cookie (page reload path).
     *
     * <p>Called by Angular on page load when the in-memory token is gone but the
     * session cookie persists. The raw cookie token is validated and returned so
     * Angular can store it in memory and resume sending it as a Bearer header.
     *
     * <p>Token rotation (when threshold is crossed) happens via
     * {@link fr.pivot.config.TokenAuthenticationFilter} on the next
     * authenticated request — not during session restore.
     *
     * @param rawCookieToken raw opaque token read from the session cookie
     * @param ip             client IP for audit
     * @param userAgent      browser user-agent for audit
     * @return {@link LoginResult} with the existing raw token (same value as cookie)
     * @throws ResponseStatusException 401 if the cookie token is invalid or expired,
     *     429 on rate limit
     */
    @Transactional
    public LoginResult restoreSession(final String rawCookieToken, final String ip, final String userAgent) {
        if (!rateLimiter.isAllowed(rateLimiter.loginIpBucket(ip), SESSION_RESTORE_MAX, SESSION_RESTORE_WINDOW)) {
            LOG.warn("event=SESSION_RESTORE_RATE_LIMITED ip={}", ip);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        final fr.pivot.auth.entity.AccessToken token = tokenService.validate(rawCookieToken)
            .orElseThrow(() -> {
                LOG.warn("event=SESSION_RESTORE_FAILED reason=invalid_token ip={}", ip);
                return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expirée");
            });

        final User user = token.getUser();
        if (!user.isActive() || user.isBlocked()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Compte désactivé");
        }

        LOG.info("event=SESSION_RESTORED userId={}", user.getId());
        return LoginResult.success(
            rawCookieToken,
            token.getExpiresAt().toEpochMilli(),
            token.getTtlSeconds(),
            UserMapper.toUserInfo(user));
    }

    /**
     * Revokes the session token associated with the current session cookie.
     *
     * <p>Silent no-op if the token is missing, invalid or already revoked —
     * logout must never fail from the client's perspective.
     *
     * @param rawCookieToken the raw session token from the HTTP-only cookie, or {@code null}
     */
    @Transactional
    public void logout(final String rawCookieToken) {
        tokenService.revokeByRawToken(rawCookieToken);
        LOG.info("event=LOGOUT");
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private void sendDeviceOtp(final User user, final String fingerprint, final String deviceName,
                                final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord(
                rateLimiter.deviceOtpBucket(user.getId().toString()),
                DEVICE_OTP_MAX, DEVICE_OTP_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        final String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        final DeviceVerifyToken dvt = new DeviceVerifyToken();
        dvt.setUser(user);
        dvt.setDeviceFingerprint(fingerprint);
        dvt.setDeviceName(deviceName);
        dvt.setOtpHash(fr.pivot.auth.util.CryptoUtils.sha256(otp));
        dvt.setExpiresAt(Instant.now().plus(deviceVerifyTtlMinutes, ChronoUnit.MINUTES));
        deviceVerifyRepo.save(dvt);
        emailService.sendDeviceVerifyEmail(user.getEmail(), user.getFirstName(), otp, deviceName);
        auditService.log(user, AuditService.DEVICE_OTP_SENT, ip, userAgent);
    }

    private Tenant saasDefaultTenant() {
        return tenantRepo.findBySlug("pivot-saas")
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Default tenant missing"));
    }
}
