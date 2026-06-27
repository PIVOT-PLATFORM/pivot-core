package fr.pivot.auth.service;

import fr.pivot.auth.entity.EmailVerification;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.dto.RegisterRequest;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.EmailVerificationRepository;
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
 * Handles user registration, email verification and re-sending of verification emails.
 *
 * <p>Single responsibility: account creation lifecycle up to the first successful login.
 *
 * <p>Rate limits are enforced via {@link RateLimiterService} before any DB access.
 * All token values sent by email are stored as SHA-256 hashes — raw tokens never persisted.
 */
@Service
public class RegistrationService {

    private static final int REGISTER_MAX_PER_IP = 5;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);
    private static final int RESEND_MAX = 3;
    private static final Duration RESEND_WINDOW = Duration.ofHours(1);
    private static final int VERIFY_MAX = 20;
    private static final Duration VERIFY_WINDOW = Duration.ofHours(1);

    private final UserRepository userRepo;
    private final TenantRepository tenantRepo;
    private final EmailVerificationRepository emailVerifRepo;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;

    private final long verificationTtlHours;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param userRepo            JPA repository for users
     * @param tenantRepo          JPA repository for tenants
     * @param emailVerifRepo      JPA repository for email verification tokens
     * @param passwordEncoder     BCrypt encoder (cost factor 12)
     * @param emailService        transactional email sender
     * @param rateLimiter         sliding-window rate limiter backed by Redis
     * @param auditService        async audit event logger
     * @param verificationTtlHours number of hours before a verification link expires
     */
    public RegistrationService(
            final UserRepository userRepo,
            final TenantRepository tenantRepo,
            final EmailVerificationRepository emailVerifRepo,
            final PasswordEncoder passwordEncoder,
            final EmailService emailService,
            final RateLimiterService rateLimiter,
            final AuditService auditService,
            @Value("${pivot.auth.verification-ttl-hours:24}") final long verificationTtlHours) {
        this.userRepo = userRepo;
        this.tenantRepo = tenantRepo;
        this.emailVerifRepo = emailVerifRepo;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.verificationTtlHours = verificationTtlHours;
    }

    /**
     * Registers a new user account on the default SaaS tenant.
     *
     * <p>Always responds the same way ("check your inbox") regardless of whether the email is
     * already taken — no 409 — to avoid email enumeration (RGPD Art. 5.1c). On a duplicate the
     * existing owner is notified by email instead, and the same BCrypt work is performed so the
     * response time does not leak account existence.
     *
     * @param req       registration payload (email, password, first/last name)
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit
     * @throws ResponseStatusException 429 on rate limit
     */
    @Transactional
    public void register(final RegisterRequest req, final String ip, final String userAgent) {
        final String registerBucket = rateLimiter.registerIpBucket(ip);
        if (!rateLimiter.checkAndRecord(registerBucket, REGISTER_MAX_PER_IP, REGISTER_WINDOW)) {
            throw new RateLimitException(Math.max(1L, rateLimiter.getRemainingSeconds(registerBucket)));
        }

        final Tenant tenant = saasDefaultTenant();
        final String email = req.email().toLowerCase();

        final User existing = userRepo
            .findByTenantIdAndEmailAndDeletedAtIsNull(tenant.getId(), email)
            .orElse(null);
        if (existing != null) {
            passwordEncoder.encode(req.password());
            if (!existing.isEmailVerified()) {
                issueVerificationReminder(existing);
            }
            return;
        }

        User user = new User();
        user.setTenant(tenant);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setFirstName(req.firstName());
        user.setLastName(req.lastName());
        user = userRepo.save(user);

        issueVerificationToken(user);
        auditService.log(user, AuditService.REGISTER, ip, userAgent);
    }

    /**
     * Validates an email verification token and marks the account as verified.
     *
     * <p>The raw token is hashed before lookup — the hash is what's stored in DB.
     * Sends a welcome email on success.
     *
     * @param rawToken  the raw token extracted from the verification link
     * @param ip        client IP for rate limiting and audit
     * @param userAgent browser user-agent for audit
     * @throws ResponseStatusException 400 on invalid/expired token, 429 on rate limit
     */
    @Transactional
    public void verifyEmail(final String rawToken, final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord(rateLimiter.verifyEmailBucket(ip), VERIFY_MAX, VERIFY_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final EmailVerification ev = emailVerifRepo
            .findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256(rawToken))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token invalide"));

        if (ev.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expiré");
        }

        ev.setUsedAt(Instant.now());
        emailVerifRepo.save(ev);

        final User user = ev.getUser();
        user.setEmailVerified(true);
        userRepo.save(user);

        emailService.sendWelcomeEmail(user.getEmail(), user.getFirstName(), EmailService.toLocale(user.getLocale()));
        auditService.log(user, AuditService.EMAIL_VERIFIED, ip, userAgent);
    }

    /**
     * Re-sends a verification email if the account exists and is not yet verified.
     *
     * <p>Silent no-op when the email is not found or already verified —
     * avoids email enumeration (RGPD Art. 5.1c).
     *
     * @param email     the email address to resend verification to
     * @param ip        client IP for rate limiting
     * @param userAgent browser user-agent
     * @throws ResponseStatusException 429 on rate limit
     */
    @Transactional
    public void resendVerification(final String email, final String ip, final String userAgent) {
        if (!rateLimiter.checkAndRecord(rateLimiter.resendVerificationBucket(ip), RESEND_MAX, RESEND_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final Tenant tenant = saasDefaultTenant();
        userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(tenant.getId(), email.toLowerCase())
            .filter(u -> !u.isEmailVerified())
            .ifPresent(this::issueVerificationToken);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private void issueVerificationReminder(final User user) {
        final String rawToken = CryptoUtils.generateSecureToken();
        final EmailVerification ev = new EmailVerification();
        ev.setUser(user);
        ev.setTokenHash(CryptoUtils.sha256(rawToken));
        ev.setExpiresAt(Instant.now().plus(verificationTtlHours, ChronoUnit.HOURS));
        emailVerifRepo.save(ev);
        emailService.sendVerificationReminderEmail(user.getEmail(), user.getFirstName(), rawToken, EmailService.toLocale(user.getLocale()));
    }

    private void issueVerificationToken(final User user) {
        final String rawToken = CryptoUtils.generateSecureToken();
        final EmailVerification ev = new EmailVerification();
        ev.setUser(user);
        ev.setTokenHash(CryptoUtils.sha256(rawToken));
        ev.setExpiresAt(Instant.now().plus(verificationTtlHours, ChronoUnit.HOURS));
        emailVerifRepo.save(ev);
        emailService.sendVerificationEmail(user.getEmail(), user.getFirstName(), rawToken, EmailService.toLocale(user.getLocale()));
    }

    private Tenant saasDefaultTenant() {
        return tenantRepo.findBySlug("pivot-saas")
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "Default tenant missing"));
    }
}
