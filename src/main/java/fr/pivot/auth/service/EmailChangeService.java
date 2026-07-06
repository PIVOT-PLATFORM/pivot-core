package fr.pivot.auth.service;

import fr.pivot.auth.dto.ChangeEmailRequest;
import fr.pivot.auth.entity.EmailChangeRequest;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.EmailChangeTargetTakenException;
import fr.pivot.auth.exception.EmailChangeTokenException;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.EmailChangeRequestRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.util.CryptoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Handles the authenticated "change my email" flow (US02.2.2) — from the confirmation-link
 * request through the single-use token confirmation that finally swaps {@code users.email}.
 *
 * <p>Mirrors the token lifecycle already used for registration email verification
 * ({@link RegistrationService}) and password reset ({@link PasswordService}): a 256-bit
 * {@link CryptoUtils#generateSecureToken() SecureRandom token} is generated, only its
 * {@link CryptoUtils#sha256(String) SHA-256 hash} is persisted, and the raw value is emailed
 * once and never logged. The current-password check reuses the same verification call as
 * {@code AccountPasswordService} (US02.2.1) — {@link PasswordEncoder#matches}.
 *
 * <p>Security posture:
 * <ul>
 *   <li><b>Anti-enumeration</b>: {@link #requestEmailChange} never surfaces whether the
 *       candidate address is already taken — the caller always gets a silent success, and the
 *       "already registered" notice is emailed exclusively to that candidate address.</li>
 *   <li><b>Old address stays active</b>: {@code users.email} is only overwritten once the new
 *       address is confirmed — until then, login still works with the old address, and login
 *       with the new one 401s because no user row has it yet.</li>
 *   <li><b>Single active request</b>: a new call to {@link #requestEmailChange} cancels
 *       whatever request was still pending for that user.</li>
 *   <li><b>Single-use token</b>: {@link #confirmEmailChange} atomically consumes the token row
 *       (guards concurrent double-clicks); any subsequent attempt on the same token is 410.</li>
 * </ul>
 */
@Service
public class EmailChangeService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailChangeService.class);

    private static final int REQUEST_MAX = 3;
    private static final Duration REQUEST_WINDOW = Duration.ofHours(1);
    private static final int CONFIRM_MAX_PER_IP = 30;
    private static final Duration CONFIRM_WINDOW = Duration.ofHours(1);

    private final UserRepository userRepo;
    private final EmailChangeRequestRepository emailChangeRepo;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecurityNotificationService securityNotificationService;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;
    private final long changeEmailTtlHours;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param userRepo                    JPA repository for users
     * @param emailChangeRepo             JPA repository for pending email-change confirmation tokens
     * @param passwordEncoder             BCrypt encoder for current-password verification
     * @param emailService                transactional email sender (confirmation link + duplicate notice)
     * @param securityNotificationService sends the "email changed" security notice (US01.5.1)
     * @param rateLimiter                 sliding-window rate limiter backed by Redis
     * @param auditService                async audit event logger
     * @param changeEmailTtlHours         number of hours before a confirmation link expires
     */
    public EmailChangeService(
            final UserRepository userRepo,
            final EmailChangeRequestRepository emailChangeRepo,
            final PasswordEncoder passwordEncoder,
            final EmailService emailService,
            final SecurityNotificationService securityNotificationService,
            final RateLimiterService rateLimiter,
            final AuditService auditService,
            @Value("${pivot.auth.email-change-ttl-hours:24}") final long changeEmailTtlHours) {
        this.userRepo = userRepo;
        this.emailChangeRepo = emailChangeRepo;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.securityNotificationService = securityNotificationService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.changeEmailTtlHours = changeEmailTtlHours;
    }

    /**
     * Initiates an email-address change for the authenticated user.
     *
     * <p>Always completes silently from the caller's point of view (the controller responds
     * 202 Accepted unconditionally) — whether or not the candidate address is already taken is
     * never observable from this call: on a duplicate, a notice is emailed to that address
     * instead, and no confirmation token is issued.
     *
     * @param userId    id of the authenticated user, resolved from the bearer token by the
     *                  controller — never from the request body
     * @param req       new-email + current-password payload
     * @param ip        client IP — rate limiting and audit
     * @param userAgent browser user-agent — audit
     * @throws ResponseStatusException 401 if the current password is wrong
     * @throws RateLimitException      429 if more than 3 requests/hour were made for this user
     */
    @Transactional
    public void requestEmailChange(
            final Long userId, final ChangeEmailRequest req, final String ip, final String userAgent) {
        final String userBucket = rateLimiter.emailChangeUserBucket(userId.toString());
        if (!rateLimiter.checkAndRecord(userBucket, REQUEST_MAX, REQUEST_WINDOW)) {
            LOG.warn("event=EMAIL_CHANGE_RATE_LIMITED userId={}", userId);
            throw new RateLimitException(Math.max(1L, rateLimiter.getRemainingSeconds(userBucket)));
        }

        final User user = userRepo.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            LOG.warn("event=EMAIL_CHANGE_REJECTED reason=bad_current_password userId={}", userId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Mot de passe actuel incorrect");
        }

        final String newEmail = req.newEmail().trim().toLowerCase();

        // "A new request cancels the previous one" — whatever else is still pending for this
        // user stops being confirmable, regardless of what happens below.
        emailChangeRepo.cancelPendingForUser(user.getId(), Instant.now());

        final Long tenantId = user.getTenant().getId();
        final Optional<User> existing = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(tenantId, newEmail);

        if (existing.isPresent()) {
            final User owner = existing.get();
            emailService.sendEmailChangeDuplicateEmail(
                newEmail, owner.getFirstName(), EmailService.toLocale(owner.getLocale()));
            auditService.log(user, AuditService.EMAIL_CHANGE_DUPLICATE_ATTEMPT, ip, userAgent);
            LOG.info("event=EMAIL_CHANGE_DUPLICATE userId={}", userId);
            return;
        }

        final String rawToken = CryptoUtils.generateSecureToken();
        final EmailChangeRequest ecr = new EmailChangeRequest();
        ecr.setUser(user);
        ecr.setNewEmail(newEmail);
        ecr.setTokenHash(CryptoUtils.sha256(rawToken));
        ecr.setExpiresAt(Instant.now().plus(changeEmailTtlHours, ChronoUnit.HOURS));
        emailChangeRepo.save(ecr);

        emailService.sendEmailChangeConfirmationEmail(
            newEmail, user.getFirstName(), rawToken, EmailService.toLocale(user.getLocale()));
        auditService.log(user, AuditService.EMAIL_CHANGE_REQUESTED, ip, userAgent);
        LOG.info("event=EMAIL_CHANGE_REQUESTED userId={}", userId);
    }

    /**
     * Confirms a pending email change from the raw token embedded in the confirmation link.
     *
     * <p>Unauthenticated by design — like {@link RegistrationService#verifyEmail} and
     * {@link PasswordService#resetPassword}, the link may be opened on a different device/
     * browser than the one that requested the change, so identity is derived solely from the
     * token, never from a bearer session.
     *
     * @param rawToken  raw token extracted from the confirmation link
     * @param ip        client IP — rate limiting, audit and the "changed" notification email
     * @param userAgent browser user-agent — audit
     * @throws RateLimitException             429 if the per-IP rate limit is exceeded
     * @throws EmailChangeTokenException      400/410 on invalid, expired or already-used token
     * @throws EmailChangeTargetTakenException 409 if the address was claimed by someone else in
     *                                          the meantime — including the narrow race where
     *                                          two concurrent confirmations both pass the
     *                                          existsBy pre-check and only one wins the
     *                                          {@code idx_users_tenant_email} unique constraint
     *                                          on flush
     */
    @Transactional
    public void confirmEmailChange(final String rawToken, final String ip, final String userAgent) {
        final String confirmBucket = rateLimiter.emailChangeConfirmIpBucket(ip);
        if (!rateLimiter.checkAndRecord(confirmBucket, CONFIRM_MAX_PER_IP, CONFIRM_WINDOW)) {
            LOG.warn("event=EMAIL_CHANGE_CONFIRM_RATE_LIMITED ip={}", ip);
            throw new RateLimitException(Math.max(1L, rateLimiter.getRemainingSeconds(confirmBucket)));
        }

        final EmailChangeRequest ecr = emailChangeRepo.findByTokenHash(CryptoUtils.sha256(rawToken))
            .orElseThrow(() -> new EmailChangeTokenException(EmailChangeTokenException.Reason.INVALID));

        if (ecr.getUsedAt() != null || ecr.getCancelledAt() != null) {
            throw new EmailChangeTokenException(EmailChangeTokenException.Reason.ALREADY_USED);
        }
        if (ecr.getExpiresAt().isBefore(Instant.now())) {
            throw new EmailChangeTokenException(EmailChangeTokenException.Reason.EXPIRED);
        }

        final Instant now = Instant.now();
        if (emailChangeRepo.markUsed(ecr.getId(), now) == 0) {
            // Another request (a concurrent click, or a cancellation) won the race.
            throw new EmailChangeTokenException(EmailChangeTokenException.Reason.ALREADY_USED);
        }

        final User user = ecr.getUser();
        final String oldEmail = user.getEmail();
        final String newEmail = ecr.getNewEmail();

        // Re-check uniqueness right before applying: guards the rare race where the target
        // address was claimed by a different account after this link was issued. The token is
        // already consumed above regardless of the outcome here (single-use, no retry on the
        // same link) — the user must submit a fresh request to try again.
        if (userRepo.existsByTenantIdAndEmailAndDeletedAtIsNull(user.getTenant().getId(), newEmail)) {
            LOG.warn("event=EMAIL_CHANGE_TARGET_TAKEN userId={}", user.getId());
            auditService.log(user, AuditService.EMAIL_CHANGE_TARGET_TAKEN, ip, userAgent);
            throw new EmailChangeTargetTakenException();
        }

        user.setEmail(newEmail);
        try {
            // Flushed immediately (not deferred to commit) so a concurrent confirmation that
            // also raced past the existsBy check above — two links for the same target,
            // confirmed within the same instant — surfaces here as a catchable exception instead
            // of failing the transaction commit after this method has already returned. The
            // unique index idx_users_tenant_email (tenant_id, email) is the actual source of
            // truth; the existsBy check above is only a fast path that narrows the race window.
            userRepo.saveAndFlush(user);
        } catch (final DataIntegrityViolationException _) {
            LOG.warn("event=EMAIL_CHANGE_TARGET_TAKEN_RACE userId={}", user.getId());
            auditService.log(user, AuditService.EMAIL_CHANGE_TARGET_TAKEN, ip, userAgent);
            throw new EmailChangeTargetTakenException();
        }

        securityNotificationService.notifyEmailChanged(user, oldEmail, newEmail, now, ip);
        auditService.log(user, AuditService.EMAIL_CHANGE_CONFIRMED, ip, userAgent);
        LOG.info("event=EMAIL_CHANGE_CONFIRMED userId={}", user.getId());
    }
}
