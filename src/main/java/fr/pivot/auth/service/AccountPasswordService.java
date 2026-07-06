package fr.pivot.auth.service;

import fr.pivot.auth.dto.ChangePasswordRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.ChangePasswordRateLimitException;
import fr.pivot.auth.exception.InvalidCurrentPasswordException;
import fr.pivot.auth.mapper.UserMapper;
import fr.pivot.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Handles the authenticated "change my password" flow (US02.2.1) — distinct from
 * {@link PasswordService}, which owns the unauthenticated forgot/reset-password flow.
 *
 * <p>Identity is always resolved from the caller's bearer token ({@link
 * fr.pivot.auth.controller.AccountController} passes only the resolved {@code userId} in — never
 * a body-supplied value), so this service never trusts a client-supplied user identifier.
 *
 * <p>Security posture on success:
 * <ul>
 *   <li>Every active session token for the user is revoked — including the very token that
 *       authenticated this request ({@link TokenService#revokeAllForUser(Long)}). A password
 *       change is treated as a full credential-rotation security event: the current token is
 *       not assumed safe just because it was valid a moment ago (defense in depth against a
 *       token that was already leaked but not yet abused).</li>
 *   <li>A brand-new opaque token is issued immediately afterwards ({@link
 *       TokenService#issue}) and returned in the 200 response body (plus session cookie), so
 *       the user's session on the current device is preserved from a UX standpoint — they are
 *       not logged out — even though the token value itself changes. This reconciles the two
 *       ACs "revocation includes the current token, a new one is issued and returned" and "the
 *       current session is preserved (only other sessions revoked)": every previous token
 *       (current one included) stops working, and it is immediately replaced so the caller's
 *       browsing session keeps working uninterrupted.</li>
 * </ul>
 *
 * <p>Rate limiting is keyed independently by user id and by client IP (5 attempts / 15 min
 * each, {@link RateLimiterService}). Both the 401 (wrong current password) and 429
 * (rate-limited) paths share the exact same message text — an attacker probing the endpoint
 * cannot distinguish "wrong password" from "rate limited" from the response body alone.
 */
@Service
public class AccountPasswordService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountPasswordService.class);

    /**
     * Message-bundle key for the text shared verbatim between the 401 and 429 responses —
     * anti-enumeration: identical wording regardless of which of the two conditions actually
     * triggered. Resolved via {@link MessageSource} (bundle {@code messages.properties}) rather
     * than a hardcoded literal, the same mechanism {@link EmailService} uses for every other
     * user-facing string in this codebase — kept in French regardless of the caller's locale, on
     * a par with the flow's current (non-localized) behaviour.
     */
    private static final String AUTH_FAILURE_MESSAGE_KEY = "account.change-password.auth-failure";

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final SecurityNotificationService securityNotificationService;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;
    private final MessageSource messageSource;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param userRepo                    JPA repository for users
     * @param passwordEncoder             BCrypt encoder for current-password verification and
     *                                    new-hash storage
     * @param tokenService                revokes existing sessions and issues the replacement token
     * @param securityNotificationService sends the "password changed" confirmation email (US01.5.1)
     * @param rateLimiter                 sliding-window rate limiter backed by Redis
     * @param auditService                async audit event logger
     * @param messageSource               resolves the shared anti-enumeration message text
     */
    public AccountPasswordService(
            final UserRepository userRepo,
            final PasswordEncoder passwordEncoder,
            final TokenService tokenService,
            final SecurityNotificationService securityNotificationService,
            final RateLimiterService rateLimiter,
            final AuditService auditService,
            final MessageSource messageSource) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.securityNotificationService = securityNotificationService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.messageSource = messageSource;
    }

    /**
     * Resolves the shared anti-enumeration message text (identical for the 401 and 429 cases).
     *
     * @return the message text, always in French — this flow does not vary by caller locale
     */
    private String authFailureMessage() {
        return messageSource.getMessage(AUTH_FAILURE_MESSAGE_KEY, null, Locale.FRENCH);
    }

    /**
     * Changes the password of the currently authenticated user.
     *
     * @param userId    id of the authenticated user, resolved from the bearer token by the
     *                  controller — never from the request body
     * @param req       current + new password payload
     * @param ip        client IP — rate limiting and audit
     * @param userAgent browser user-agent — new token metadata and audit
     * @return a {@link LoginResult} carrying the freshly issued replacement token
     * @throws InvalidCurrentPasswordException  401 if the current password is wrong
     * @throws ChangePasswordRateLimitException 429 if the per-user or per-IP rate limit is
     *                                           exceeded (message identical to the 401 case)
     */
    @Transactional
    public LoginResult changePassword(
            final Long userId,
            final ChangePasswordRequest req,
            final String ip,
            final String userAgent) {
        final String userBucket = rateLimiter.changePasswordUserBucket(userId.toString());
        final String ipBucket = rateLimiter.changePasswordIpBucket(ip);

        if (!rateLimiter.isAllowed(userBucket, MAX_ATTEMPTS, WINDOW)
                || !rateLimiter.isAllowed(ipBucket, MAX_ATTEMPTS, WINDOW)) {
            final long retryAfter = Math.max(
                rateLimiter.getRemainingSeconds(userBucket),
                rateLimiter.getRemainingSeconds(ipBucket));
            LOG.warn("event=CHANGE_PASSWORD_RATE_LIMITED userId={}", userId);
            throw new ChangePasswordRateLimitException(
                authFailureMessage(), Math.max(retryAfter, 1L));
        }

        final User user = userRepo.findById(userId)
            .orElseThrow(() -> new InvalidCurrentPasswordException(authFailureMessage()));

        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            rateLimiter.recordAttempt(userBucket, WINDOW);
            rateLimiter.recordAttempt(ipBucket, WINDOW);
            auditService.log(user, AuditService.CHANGE_PASSWORD_FAILED, ip, userAgent);
            LOG.warn("event=CHANGE_PASSWORD_FAILED reason=bad_current_password userId={}", userId);
            throw new InvalidCurrentPasswordException(authFailureMessage());
        }

        rateLimiter.reset(userBucket);
        rateLimiter.reset(ipBucket);

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepo.save(user);

        // Security event: revoke EVERY active session — including the one that authenticated
        // this very request — then immediately issue a fresh one for the current device.
        tokenService.revokeAllForUser(user.getId());
        final TokenService.TokenIssueResult issued = tokenService.issue(
            user, null, null, userAgent, ip, AuthMethod.PASSWORD, false);

        securityNotificationService.notifyPasswordChanged(user, Instant.now(), ip);
        auditService.log(user, AuditService.CHANGE_PASSWORD, ip, userAgent);
        LOG.info("event=CHANGE_PASSWORD_SUCCESS userId={}", userId);

        return LoginResult.success(
            issued.rawToken(), issued.expiresAt().toEpochMilli(), issued.ttlSeconds(), UserMapper.toUserInfo(user));
    }
}
