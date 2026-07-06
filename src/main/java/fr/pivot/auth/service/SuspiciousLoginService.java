package fr.pivot.auth.service;

import fr.pivot.auth.entity.SuspiciousLoginToken;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.SuspiciousLoginTokenRepository;
import fr.pivot.auth.util.CryptoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Detects logins from a device unknown to {@code trusted_devices} and sends a passive
 * "suspicious login" email alert (US01.4.3a) — distinct from the US01.4.1 device-OTP gate.
 *
 * <p><b>How this differs from the US01.4.1 gate:</b> {@link SessionService#login} already blocks
 * an unknown device behind a 6-digit email OTP, but only when the {@code MFA_NEW_DEVICE_OTP}
 * feature flag is enabled, or for {@code ROLE_SUPER_ADMIN} accounts. For every other login — the
 * default posture for regular tenants — an unknown device today authenticates silently, with no
 * signal at all to the account owner. This service closes that gap: it never blocks the login
 * (credentials were already verified by the time it is invoked), it only alerts. Both features
 * share {@code trusted_devices} as their single source of truth for "known device", but never
 * fire on the same request: {@link SessionService} only calls {@link #alertIfUnknownDevice} on
 * the branch where the OTP gate did <em>not</em> trigger.
 *
 * <p><b>Token shape:</b> deliberately mirrors {@code password_reset_tokens} (raw 256-bit value,
 * SHA-256 hash, {@code expiresAt}/{@code usedAt}) rather than {@code device_verify_tokens}' 6-
 * digit HMAC OTP — the "Not me" link is clicked from an email, never typed in by hand, the same
 * primitive used by every other link-based single-use token in this codebase (password reset,
 * email-change confirmation, account-deletion cancellation).
 *
 * <p><b>"Not me" confirmation:</b> {@link #confirmNotMe} deliberately requires the account's
 * current password — a full re-authentication — before taking any action. The token alone (from
 * clicking the link) is never sufficient: if only the mailbox were compromised, the token would
 * leak but not the password. On success, the flagged device's trust is revoked and every active
 * session is terminated — the same remediation posture as a confirmed password compromise
 * ({@link AccountPasswordService}, {@link PasswordService#resetPassword}).
 */
@Service
public class SuspiciousLoginService {

    private static final Logger LOG = LoggerFactory.getLogger(SuspiciousLoginService.class);

    private static final int NOT_ME_TTL_DEFAULT_MINUTES = 60;
    private static final int ALERT_MAX = 10;
    private static final Duration ALERT_WINDOW = Duration.ofHours(1);
    private static final int CONFIRM_MAX = 10;
    private static final Duration CONFIRM_WINDOW = Duration.ofHours(1);

    private final SuspiciousLoginTokenRepository tokenRepo;
    private final FeatureFlagRepository featureFlagRepo;
    private final EmailService emailService;
    private final AuditService auditService;
    private final RateLimiterService rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final TrustedDeviceService trustedDeviceService;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param tokenRepo            JPA repository for "Not me" tokens
     * @param featureFlagRepo      admin-configurable settings (SUSPICIOUS_LOGIN_OTP_TTL_MINUTES)
     * @param emailService         transactional email sender
     * @param auditService         async audit event logger
     * @param rateLimiter          sliding-window rate limiter backed by Redis
     * @param passwordEncoder      BCrypt encoder for the "Not me" current-password re-auth check
     * @param tokenService         revokes every active session on a confirmed "Not me"
     * @param trustedDeviceService revokes trust for the flagged device on a confirmed "Not me"
     */
    public SuspiciousLoginService(
            final SuspiciousLoginTokenRepository tokenRepo,
            final FeatureFlagRepository featureFlagRepo,
            final EmailService emailService,
            final AuditService auditService,
            final RateLimiterService rateLimiter,
            final PasswordEncoder passwordEncoder,
            final TokenService tokenService,
            final TrustedDeviceService trustedDeviceService) {
        this.tokenRepo = tokenRepo;
        this.featureFlagRepo = featureFlagRepo;
        this.emailService = emailService;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.trustedDeviceService = trustedDeviceService;
    }

    /**
     * Sends the passive suspicious-login alert when {@code deviceAlreadyTrusted} is {@code
     * false}. No-op (no email, no token, no audit) when the device is already known — the
     * caller ({@link SessionService}) is expected to only invoke this on the untrusted-device
     * branch, but the guard is repeated here too (defence in depth) so this service is safe to
     * call unconditionally and independently testable.
     *
     * @param user                 the authenticated user (credentials already verified)
     * @param deviceAlreadyTrusted whether {@code fingerprint} is already known in
     *                             {@code trusted_devices} for this user
     * @param fingerprint          the device fingerprint that authenticated
     * @param deviceName           human-readable device label, or {@code null}
     * @param ip                   client IP for audit and the token's forensic metadata
     * @param userAgent            browser user-agent for audit
     */
    @Transactional
    public void alertIfUnknownDevice(
            final User user,
            final boolean deviceAlreadyTrusted,
            final String fingerprint,
            final String deviceName,
            final String ip,
            final String userAgent) {
        if (deviceAlreadyTrusted) {
            return;
        }

        auditService.log(user, AuditService.SUSPICIOUS_LOGIN_DETECTED, ip, userAgent);

        if (!rateLimiter.checkAndRecord(
                rateLimiter.suspiciousLoginAlertBucket(user.getId().toString()), ALERT_MAX, ALERT_WINDOW)) {
            LOG.warn("event=SUSPICIOUS_LOGIN_ALERT_SKIPPED reason=rate_limited userId={}", user.getId());
            return;
        }

        final String rawToken = CryptoUtils.generateSecureToken();
        final SuspiciousLoginToken token = new SuspiciousLoginToken();
        token.setUser(user);
        token.setDeviceFingerprint(fingerprint);
        token.setDeviceName(deviceName);
        token.setIpAddress(ip);
        token.setTokenHash(CryptoUtils.sha256(rawToken));
        token.setExpiresAt(Instant.now().plus(
            featureFlagRepo.getInt("SUSPICIOUS_LOGIN_OTP_TTL_MINUTES", NOT_ME_TTL_DEFAULT_MINUTES),
            ChronoUnit.MINUTES));
        tokenRepo.save(token);

        emailService.sendSuspiciousLoginAlertEmail(
            user.getEmail(), user.getFirstName(), deviceName, Instant.now(), rawToken,
            EmailService.toLocale(user.getLocale()));

        LOG.info("event=SUSPICIOUS_LOGIN_ALERT_SENT userId={}", user.getId());
    }

    /**
     * Confirms a "Not me" report via full re-authentication (current password required).
     *
     * <p>On success: the flagged device's trust is revoked and every active session for the
     * user is terminated (force logout everywhere) — the same remediation posture as a confirmed
     * password compromise elsewhere in this codebase.
     *
     * @param rawToken        raw token from the email link
     * @param currentPassword the account's current password
     * @param ip              client IP for rate limiting and audit
     * @param userAgent       browser user-agent for audit
     * @throws ResponseStatusException 429 on rate limit, 400 on invalid/expired/already-used
     *     token, 401 if the current password does not match
     */
    @Transactional
    public void confirmNotMe(
            final String rawToken, final String currentPassword, final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord(
                rateLimiter.suspiciousLoginConfirmIpBucket(ip), CONFIRM_MAX, CONFIRM_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final SuspiciousLoginToken token = tokenRepo
            .findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256(rawToken))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalide"));

        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expiré");
        }

        final User user = token.getUser();
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            auditService.log(user, AuditService.SUSPICIOUS_LOGIN_NOT_ME_FAILED, ip, userAgent);
            LOG.warn("event=SUSPICIOUS_LOGIN_NOT_ME_FAILED reason=bad_current_password userId={}", user.getId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Mot de passe invalide");
        }

        if (tokenRepo.markUsed(token.getId(), Instant.now()) == 0) {
            // Another request consumed the token between the SELECT and this UPDATE
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalide");
        }

        trustedDeviceService.revoke(user, token.getDeviceFingerprint());
        tokenService.revokeAllForUser(user.getId());

        auditService.log(user, AuditService.SUSPICIOUS_LOGIN_NOT_ME_CONFIRMED, ip, userAgent);
        LOG.warn("event=SUSPICIOUS_LOGIN_NOT_ME_CONFIRMED userId={}", user.getId());
    }
}
