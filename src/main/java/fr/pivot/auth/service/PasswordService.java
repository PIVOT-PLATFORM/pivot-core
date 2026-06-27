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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Handles the forgot-password and reset-password flows.
 *
 * <p>Single responsibility: credential recovery — from token issuance through
 * hash replacement and session invalidation.
 *
 * <p>{@code forgotPassword} is deliberately silent on unknown emails to avoid
 * email enumeration (RGPD Art. 5.1c). All active session tokens are revoked on
 * password change to force re-authentication on all devices.
 *
 * @see SessionService for login and session management
 */
@Service
public class PasswordService {

    private static final int FORGOT_MAX = 5;
    private static final Duration FORGOT_WINDOW = Duration.ofHours(1);
    private static final int RESET_MAX = 10;
    private static final Duration RESET_WINDOW = Duration.ofHours(1);

    private static final int PASSWORD_RESET_TTL_DEFAULT = 15;

    private final UserRepository userRepo;
    private final TenantRepository tenantRepo;
    private final PasswordResetTokenRepository passwordResetRepo;
    private final FeatureFlagRepository featureFlagRepo;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param userRepo          JPA repository for users
     * @param tenantRepo        JPA repository for tenants
     * @param passwordResetRepo JPA repository for password reset tokens
     * @param featureFlagRepo   admin-configurable settings (PASSWORD_RESET_TTL_MINUTES)
     * @param tokenService      revokes all sessions for a user on password change
     * @param passwordEncoder   BCrypt encoder for new password hashing
     * @param emailService      transactional email sender
     * @param rateLimiter       sliding-window rate limiter backed by Redis
     * @param auditService      async audit event logger
     */
    public PasswordService(
            final UserRepository userRepo,
            final TenantRepository tenantRepo,
            final PasswordResetTokenRepository passwordResetRepo,
            final FeatureFlagRepository featureFlagRepo,
            final TokenService tokenService,
            final PasswordEncoder passwordEncoder,
            final EmailService emailService,
            final RateLimiterService rateLimiter,
            final AuditService auditService) {
        this.userRepo = userRepo;
        this.tenantRepo = tenantRepo;
        this.passwordResetRepo = passwordResetRepo;
        this.featureFlagRepo = featureFlagRepo;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    /**
     * Checks whether a reset token is still valid (unused and not expired).
     * Read-only — does not consume the token or count against the rate limit.
     *
     * @param rawToken raw token from the email link
     * @throws ResponseStatusException 400 if the token is invalid, expired or already used
     */
    @Transactional(readOnly = true)
    public void checkResetToken(final String rawToken) {
        final PasswordResetToken prt = passwordResetRepo
            .findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256(rawToken))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalide ou déjà utilisé"));
        if (prt.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expiré");
        }
    }

    /**
     * Initiates the password reset flow by sending a reset link to the registered email.
     *
     * <p>Always returns silently regardless of whether the email is found —
     * never reveals account existence (RGPD Art. 5.1c / no enumeration).
     *
     * @param req       forgot-password payload (email address)
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit
     */
    @Transactional
    public void forgotPassword(final ForgotPasswordRequest req, final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord(rateLimiter.forgotPasswordBucket(ip), FORGOT_MAX, FORGOT_WINDOW)) {
            return;
        }

        final Tenant tenant = saasDefaultTenant();
        userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(tenant.getId(), req.email().toLowerCase())
            .ifPresent(user -> {
                if (!user.isEmailVerified() || !user.isActive()) {
                    return;
                }
                final String rawToken = CryptoUtils.generateSecureToken();
                final PasswordResetToken prt = new PasswordResetToken();
                prt.setUser(user);
                prt.setTokenHash(CryptoUtils.sha256(rawToken));
                prt.setExpiresAt(Instant.now().plus(
                    featureFlagRepo.getInt("PASSWORD_RESET_TTL_MINUTES", PASSWORD_RESET_TTL_DEFAULT),
                    ChronoUnit.MINUTES));
                passwordResetRepo.save(prt);
                emailService.sendPasswordResetEmail(user.getEmail(), user.getFirstName(), rawToken);
                auditService.log(user, AuditService.PASSWORD_RESET_REQUEST, ip, userAgent);
            });
    }

    /**
     * Resets the user's password using a valid reset token.
     *
     * <p>Marks the token as used, hashes and stores the new password, then revokes
     * all active session tokens to force re-authentication on all devices.
     *
     * @param req       reset-password payload (raw token + new password)
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit
     * @throws ResponseStatusException 400 on invalid/expired token, 429 on rate limit
     */
    @Transactional
    public void resetPassword(final ResetPasswordRequest req, final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord(rateLimiter.resetPasswordBucket(ip), RESET_MAX, RESET_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final PasswordResetToken prt = passwordResetRepo
            .findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256(req.token()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalide"));

        if (prt.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expiré");
        }

        final Instant now = Instant.now();
        if (passwordResetRepo.markUsed(prt.getId(), now) == 0) {
            // Another request consumed the token between the SELECT and this UPDATE
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalide");
        }

        final User user = prt.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepo.save(user);

        // Revoke all active sessions — password change is a security event
        tokenService.revokeAllForUser(user.getId());
        emailService.sendPasswordChangedEmail(user.getEmail(), user.getFirstName(), now, ip);
        auditService.log(user, AuditService.PASSWORD_RESET, ip, userAgent);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private Tenant saasDefaultTenant() {
        return tenantRepo.findBySlug("pivot-saas")
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Default tenant missing"));
    }
}
