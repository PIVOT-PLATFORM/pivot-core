package fr.pivot.auth.service;

import fr.pivot.auth.dto.DeviceOtpRequest;
import fr.pivot.auth.dto.LoginRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.dto.SessionDto;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.DeviceVerifyToken;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.mapper.UserMapper;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.DeviceVerifyTokenRepository;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.util.HtmlStripper;
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
import java.util.List;

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
 * <p>On every login where that gate does not trigger (flag disabled, or a non-{@code
 * ROLE_SUPER_ADMIN} account) and the device fingerprint is unknown to {@code trusted_devices},
 * {@link SuspiciousLoginService} sends a passive, non-blocking "suspicious login" email alert
 * instead (US01.4.3a) — the device is then marked trusted so the same device does not re-alert
 * on the next login. This is a distinct, additive feature from the US01.4.1 OTP gate above: the
 * two never fire on the same request.
 *
 * <p>Also backs the "active sessions" self-service screen (US02.2.3):
 * {@link #listSessions(User, Long)}, {@link #revokeSession(User, Long, Long)} and
 * {@link #revokeAllSessionsExceptCurrent(User, Long)} — listing and revocation are userId-scoped
 * from the bearer token's resolved {@link User}, never from a client-supplied identifier.
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
    /** Hard cap on wrong OTP submissions for a single pending device-verification token. */
    private static final int DEVICE_OTP_MAX_ATTEMPTS = 5;
    private static final int SESSION_RESTORE_MAX = 30;
    private static final Duration SESSION_RESTORE_WINDOW = Duration.ofMinutes(5);

    /**
     * Max length re-applied to {@code device} when mapping to {@link SessionDto} — defence in
     * depth alongside the sanitization already applied by {@link TokenService} at issuance time,
     * in case any row was ever written by another path.
     */
    private static final int DEVICE_NAME_MAX_LENGTH = 200;

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
    private final AccessTokenRepository tokenRepo;
    private final SuspiciousLoginService suspiciousLoginService;

    private static final int DEVICE_VERIFY_TTL_DEFAULT = 15;
    private final String otpSecret;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Pre-computed BCrypt hash used as a decoy when the email is unknown, so the response
     * time of a failed login is the same whether or not the account exists (no timing oracle).
     */
    private final String dummyPasswordHash;

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
     * @param tokenRepo              direct read access to {@link AccessToken} rows for the
     *                               active-sessions screen (list / ownership check / bulk revoke)
     * @param suspiciousLoginService sends the passive "unknown device" alert (US01.4.3a) on the
     *                               login branch where the US01.4.1 OTP gate does not apply
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
            @Value("${pivot.auth.otp-secret:}") final String otpSecret,
            final AccessTokenRepository tokenRepo,
            final SuspiciousLoginService suspiciousLoginService) {
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
        this.tokenRepo = tokenRepo;
        this.suspiciousLoginService = suspiciousLoginService;
        this.otpSecret = fr.pivot.auth.util.CryptoUtils.resolveOtpSecret(otpSecret);
        if (otpSecret == null || otpSecret.isBlank()) {
            LOG.warn("event=OTP_SECRET_EPHEMERAL reason=pivot.auth.otp-secret_unset scope=session-device-otp");
        }
        // Decoy hash for the unknown-email timing path — random content (value is irrelevant).
        this.dummyPasswordHash = passwordEncoder.encode(fr.pivot.auth.util.CryptoUtils.generateSecureToken());
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

        // Always run BCrypt — against the real hash, or a decoy when the email is unknown —
        // so the response time does not reveal whether the account exists (no enumeration oracle).
        final boolean credentialsValid = user != null
            ? passwordEncoder.matches(req.password(), user.getPasswordHash())
            : runDecoyPasswordCheck(req.password());

        if (!credentialsValid) {
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
        final boolean hasFingerprint = fingerprint != null && !fingerprint.isBlank();
        final boolean mfaNewDevice = featureFlagRepo.isEnabled("MFA_NEW_DEVICE_OTP");
        final boolean isSuperAdmin = "ROLE_SUPER_ADMIN".equals(user.getRole());

        boolean deviceTrusted = false;
        if (hasFingerprint) {
            deviceTrusted = trustedDeviceService.isTrusted(user, fingerprint);
            if (!deviceTrusted && (mfaNewDevice || isSuperAdmin)) {
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

        // US01.4.3a — passive alert for the branch the US01.4.1 OTP gate does not cover
        // (flag disabled, or a non-ROLE_SUPER_ADMIN account): never blocks the login above.
        if (hasFingerprint) {
            suspiciousLoginService.alertIfUnknownDevice(user, deviceTrusted, fingerprint, req.deviceName(), ip, userAgent);
            if (!deviceTrusted) {
                // Device is now known — the alert already told the owner about it once;
                // it must not re-fire on every subsequent login from this same device.
                trustedDeviceService.trust(user, fingerprint, req.deviceName());
            }
        }

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

        // Hard per-token guard: once the wrong-OTP cap is hit, this token is burned even if
        // the Redis IP/user limiter window has rolled over (defence in depth).
        if (dvt.getAttempts() >= DEVICE_OTP_MAX_ATTEMPTS) {
            LOG.warn("event=DEVICE_OTP_LOCKED userId={} attempts={}", dvt.getUser().getId(), dvt.getAttempts());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Trop de tentatives OTP");
        }

        if (!dvt.getOtpHash().equals(fr.pivot.auth.util.CryptoUtils.hmacSha256(req.otp(), otpSecret))) {
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
        if (!rateLimiter.isAllowed(rateLimiter.sessionRestoreBucket(ip), SESSION_RESTORE_MAX, SESSION_RESTORE_WINDOW)) {
            LOG.warn("event=SESSION_RESTORE_RATE_LIMITED ip={}", ip);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }
        rateLimiter.recordAttempt(rateLimiter.sessionRestoreBucket(ip), SESSION_RESTORE_WINDOW);
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

    /**
     * Lists the current user's active sessions, most recently created first (US02.2.3).
     *
     * <p>{@code userId} is always taken from the authenticated {@link User} — never from a
     * client-supplied parameter — so this can never return another user's sessions.
     *
     * @param user            the authenticated user (resolved from the bearer token)
     * @param currentTokenId  id of the {@link AccessToken} backing the current request, or
     *                        {@code null} if it could not be resolved (no session is then
     *                        flagged {@code isCurrent})
     * @return active sessions mapped to {@link SessionDto}, most recent first
     */
    @Transactional(readOnly = true)
    public List<SessionDto> listSessions(final User user, final Long currentTokenId) {
        return tokenRepo.findByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), TokenStatus.ACTIVE)
            .stream()
            .map(token -> toSessionDto(token, currentTokenId))
            .toList();
    }

    /**
     * Revokes a single session belonging to the current user (US02.2.3).
     *
     * <p>Ownership is checked first: a {@code tokenId} that does not belong to {@code user}
     * (whether it does not exist or belongs to another user) yields 404 — the response never
     * reveals whether the token exists for someone else. Only once ownership is established is
     * the current-session guard applied — revoking the session backing the request that asked
     * for the revocation is rejected with 403 (API-level protection, independent of the UI).
     *
     * @param user           the authenticated user (resolved from the bearer token)
     * @param tokenId        id of the {@link AccessToken} to revoke (path variable, untrusted)
     * @param currentTokenId id of the session backing the current request
     * @throws ResponseStatusException 404 if the token does not exist or belongs to another
     *     user; 403 if {@code tokenId} is the current session
     */
    @Transactional
    public void revokeSession(final User user, final Long tokenId, final Long currentTokenId) {
        final AccessToken token = tokenRepo.findByIdAndUserId(tokenId, user.getId())
            .filter(t -> TokenStatus.ACTIVE.equals(t.getStatus()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (token.getId().equals(currentTokenId)) {
            LOG.warn("event=SESSION_REVOKE_REJECTED reason=is_current_session userId={} tokenId={}",
                user.getId(), tokenId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Impossible de révoquer la session courante");
        }

        tokenService.revoke(token);
        LOG.info("event=SESSION_REVOKED userId={} tokenId={}", user.getId(), tokenId);
    }

    /**
     * Revokes every active session of the current user except the current one (US02.2.3).
     *
     * @param user           the authenticated user (resolved from the bearer token)
     * @param currentTokenId id of the session backing the current request — preserved
     */
    @Transactional
    public void revokeAllSessionsExceptCurrent(final User user, final Long currentTokenId) {
        final int revoked = tokenRepo.revokeAllForUserExceptToken(
            user.getId(), currentTokenId, TokenStatus.ACTIVE, TokenStatus.REVOKED);
        LOG.info("event=SESSIONS_REVOKED_ALL_EXCEPT_CURRENT userId={} count={}", user.getId(), revoked);
    }

    private SessionDto toSessionDto(final AccessToken token, final Long currentTokenId) {
        return new SessionDto(
            token.getId(),
            HtmlStripper.stripAndTruncate(token.getDeviceName(), DEVICE_NAME_MAX_LENGTH),
            token.getIpAddress(),
            token.getCreatedAt(),
            token.getExpiresAt(),
            token.getId().equals(currentTokenId));
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
        dvt.setOtpHash(fr.pivot.auth.util.CryptoUtils.hmacSha256(otp, otpSecret));
        dvt.setExpiresAt(Instant.now().plus(
            featureFlagRepo.getInt("DEVICE_VERIFY_TTL_MINUTES", DEVICE_VERIFY_TTL_DEFAULT),
            ChronoUnit.MINUTES));
        deviceVerifyRepo.save(dvt);
        emailService.sendDeviceVerifyEmail(user.getEmail(), user.getFirstName(), otp, deviceName, EmailService.toLocale(user.getLocale()));
        auditService.log(user, AuditService.DEVICE_OTP_SENT, ip, userAgent);
    }

    /**
     * Runs a BCrypt comparison against a decoy hash to burn the same CPU time as a real
     * password check. Always returns {@code false} — used when the email is unknown.
     *
     * @param rawPassword the submitted password (compared against the decoy hash)
     * @return always {@code false}
     */
    private boolean runDecoyPasswordCheck(final String rawPassword) {
        passwordEncoder.matches(rawPassword, dummyPasswordHash);
        return false;
    }

    private Tenant saasDefaultTenant() {
        return tenantRepo.findBySlug("pivot-saas")
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Default tenant missing"));
    }
}
