package fr.pivot.auth.service;

import fr.pivot.auth.dto.ForgotPasswordRequest;
import fr.pivot.auth.dto.ResetPasswordRequest;
import fr.pivot.auth.entity.PasswordResetToken;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.PasswordResetTokenRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.util.CryptoUtils;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
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

    private final UserRepository userRepo;
    private final TenantRepository tenantRepo;
    private final PasswordResetTokenRepository passwordResetRepo;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;

    private final long passwordResetTtlMinutes;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param userRepo                JPA repository for users
     * @param tenantRepo              JPA repository for tenants
     * @param passwordResetRepo       JPA repository for password reset tokens
     * @param tokenService            revokes all sessions for a user on password change
     * @param passwordEncoder         BCrypt encoder for new password hashing
     * @param emailService            transactional email sender
     * @param rateLimiter             sliding-window rate limiter backed by Redis
     * @param auditService            async audit event logger
     * @param passwordResetTtlMinutes number of minutes before a reset link expires
     */
    public PasswordService(
            final UserRepository userRepo,
            final TenantRepository tenantRepo,
            final PasswordResetTokenRepository passwordResetRepo,
            final TokenService tokenService,
            final PasswordEncoder passwordEncoder,
            final EmailService emailService,
            final RateLimiterService rateLimiter,
            final AuditService auditService,
            @Value("${pivot.auth.password-reset-ttl-minutes:60}") final long passwordResetTtlMinutes) {
        this.userRepo = userRepo;
        this.tenantRepo = tenantRepo;
        this.passwordResetRepo = passwordResetRepo;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.passwordResetTtlMinutes = passwordResetTtlMinutes;
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
                prt.setExpiresAt(Instant.now().plus(passwordResetTtlMinutes, ChronoUnit.MINUTES));
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

        prt.setUsedAt(Instant.now());
        passwordResetRepo.save(prt);

        final User user = prt.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepo.save(user);

        // Revoke all active sessions — password change is a security event
        tokenService.revokeAllForUser(user.getId());
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
