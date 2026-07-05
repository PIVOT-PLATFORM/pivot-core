package fr.pivot.account.service;

import fr.pivot.account.dto.AccountDeletionRequestDto;
import fr.pivot.account.entity.AccountDeletionOtp;
import fr.pivot.account.entity.AccountDeletionRequest;
import fr.pivot.account.entity.DeletionConfirmationMethod;
import fr.pivot.account.repository.AccountDeletionOtpRepository;
import fr.pivot.account.repository.AccountDeletionRequestRepository;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.FeatureFlagRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.AuditService;
import fr.pivot.auth.service.EmailService;
import fr.pivot.auth.service.RateLimiterService;
import fr.pivot.auth.service.TokenService;
import fr.pivot.auth.util.CryptoUtils;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Orchestrates the RGPD Art. 17 "right to erasure" account-deletion flow (US02.2.4):
 * dual-path confirmation (current password for LOCAL accounts, emailed 6-digit OTP for
 * OIDC / Google-only accounts), immediate effects (session revocation, PENDING_DELETION status,
 * confirmation email with the effective purge date), cancellation via a single-use emailed
 * link, and the scheduled anonymization once the grace period elapses.
 *
 * <p><strong>Why {@code users.deleted_at}/{@code scheduled_deletion_at} carry PENDING_DELETION,
 * not a dedicated status enum:</strong> both columns already existed since the V1 baseline
 * schema, reserved exactly for this feature. Setting {@link User#getDeletedAt()} immediately at
 * request time (rather than only once the row is finally anonymized) is what makes the account
 * "invisible to admin reads" ({@code UserSpecifications#notDeleted()}, already applied by {@code
 * AdminUserService}) and unable to log back in — {@code SessionService}, {@code
 * GoogleAuthService} and {@code OidcAuthService} already resolve the login candidate through a
 * {@code *AndDeletedAtIsNull} lookup, so a PENDING_DELETION account naturally 401s/403s at login
 * exactly like an unknown account, with zero changes to those flows. {@link
 * User#getAnonymizedAt()} is the only new signal, distinguishing "pending, within grace period"
 * from "purged".
 *
 * <p><strong>Password vs OTP:</strong> this class never depends on the sibling
 * {@code AccountPasswordService} (US02.2.1) — like {@code EmailChangeService} (US02.2.2), it
 * calls {@link PasswordEncoder#matches} directly. The OTP path mirrors {@code SessionService}'s
 * device-verification OTP (US01.4.1) at the primitive level (HMAC-SHA256 via the shared
 * {@code pivot.auth.otp-secret}, bounded wrong-attempt count, short TTL) through a dedicated
 * {@link AccountDeletionOtp} table — there is no device/fingerprint concept here.
 */
@Service
public class AccountDeletionService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionService.class);

    private static final int DEFAULT_GRACE_DAYS = 30;
    private static final int DEFAULT_OTP_TTL_MINUTES = 10;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final int OTP_RATE_LIMIT_MAX = 5;
    private static final Duration OTP_RATE_LIMIT_WINDOW = Duration.ofMinutes(15);

    private static final String MSG_CONFIRMATION_REJECTED = "Confirmation de suppression invalide";
    private static final String MSG_ALREADY_PENDING = "Une suppression est déjà en cours pour ce compte";

    private final UserRepository userRepo;
    private final AccountDeletionRequestRepository deletionRequestRepo;
    private final AccountDeletionOtpRepository deletionOtpRepo;
    private final FeatureFlagRepository featureFlagRepo;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AvatarStorageService avatarStorageService;
    private final EmailService emailService;
    private final RateLimiterService rateLimiter;
    private final AuditService auditService;
    private final String otpSecret;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Self-reference (proxied) used to invoke {@link #anonymize(Long)} through the Spring proxy
     * from within {@link #anonymizeDueAccounts()} — a direct {@code this.anonymize(...)} call
     * would bypass the proxy, disabling the per-user {@code @Transactional} boundary that lets
     * one user's failure not roll back the whole batch. Mirrors {@code TokenService} / {@code
     * AuditService} / {@code DataExportService}.
     */
    private final AccountDeletionService self;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param userRepo             JPA repository for users
     * @param deletionRequestRepo  JPA repository for deletion requests (cancellation tokens)
     * @param deletionOtpRepo      JPA repository for OTP confirmations
     * @param featureFlagRepo      admin-configurable settings (grace period, OTP TTL)
     * @param passwordEncoder      BCrypt encoder for current-password verification
     * @param tokenService         revokes every active session immediately on request
     * @param avatarStorageService deletes the avatar file at anonymization time
     * @param emailService         transactional email sender
     * @param rateLimiter          sliding-window rate limiter backed by Redis
     * @param auditService         async audit event logger
     * @param otpSecret            HMAC key for OTP hashing ({@code pivot.auth.otp-secret})
     * @param self                 self proxy for per-user transactional dispatch
     */
    public AccountDeletionService(
            final UserRepository userRepo,
            final AccountDeletionRequestRepository deletionRequestRepo,
            final AccountDeletionOtpRepository deletionOtpRepo,
            final FeatureFlagRepository featureFlagRepo,
            final PasswordEncoder passwordEncoder,
            final TokenService tokenService,
            final AvatarStorageService avatarStorageService,
            final EmailService emailService,
            final RateLimiterService rateLimiter,
            final AuditService auditService,
            @Value("${pivot.auth.otp-secret:}") final String otpSecret,
            final @Lazy AccountDeletionService self) {
        this.userRepo = userRepo;
        this.deletionRequestRepo = deletionRequestRepo;
        this.deletionOtpRepo = deletionOtpRepo;
        this.featureFlagRepo = featureFlagRepo;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.avatarStorageService = avatarStorageService;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.self = self;
        this.otpSecret = CryptoUtils.resolveOtpSecret(otpSecret);
        if (otpSecret == null || otpSecret.isBlank()) {
            LOG.warn("event=OTP_SECRET_EPHEMERAL reason=pivot.auth.otp-secret_unset scope=account-deletion");
        }
    }

    /**
     * Determines which confirmation path {@code user} must use.
     *
     * @param user the authenticated user
     * @return {@link DeletionConfirmationMethod#PASSWORD} if the account has a local password,
     *     {@link DeletionConfirmationMethod#OTP} otherwise
     */
    public DeletionConfirmationMethod confirmationMethod(final User user) {
        return user.getPasswordHash() != null ? DeletionConfirmationMethod.PASSWORD : DeletionConfirmationMethod.OTP;
    }

    /**
     * Emails a 6-digit OTP to confirm an account-deletion request — OIDC / Google-only accounts
     * only (no local password to verify instead).
     *
     * @param user      the authenticated user
     * @param ip        client IP — rate limiting and audit
     * @param userAgent browser user-agent — audit
     * @throws ResponseStatusException 400 if the account has a local password (use
     *     {@code currentPassword} on {@code DELETE /account} instead), 409 if a deletion is
     *     already pending, 429 if rate-limited
     */
    @Transactional
    public void requestOtp(final User user, final String ip, final String userAgent) {
        if (confirmationMethod(user) == DeletionConfirmationMethod.PASSWORD) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Ce compte utilise un mot de passe local — confirmez avec votre mot de passe actuel");
        }
        if (user.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, MSG_ALREADY_PENDING);
        }

        final String bucket = rateLimiter.accountDeletionOtpBucket(user.getId().toString());
        if (!rateLimiter.checkAndRecord(bucket, OTP_RATE_LIMIT_MAX, OTP_RATE_LIMIT_WINDOW)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        final String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        final AccountDeletionOtp entity = new AccountDeletionOtp();
        entity.setUser(user);
        entity.setOtpHash(CryptoUtils.hmacSha256(otp, otpSecret));
        final int ttlMinutes = featureFlagRepo.getInt("ACCOUNT_DELETION_OTP_TTL_MINUTES", DEFAULT_OTP_TTL_MINUTES);
        entity.setExpiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES));
        deletionOtpRepo.save(entity);

        emailService.sendAccountDeletionOtpEmail(
            user.getEmail(), user.getFirstName(), otp, EmailService.toLocale(user.getLocale()));
        auditService.log(user, AuditService.ACCOUNT_DELETION_OTP_SENT, ip, userAgent);
        LOG.info("event=ACCOUNT_DELETION_OTP_SENT userId={}", user.getId());
    }

    /**
     * Confirms and immediately applies an account-deletion request: revokes every active
     * session, moves the account to PENDING_DELETION, starts the grace period, and emails the
     * confirmation with the effective purge date and the cancellation link.
     *
     * @param user      the authenticated user
     * @param req       the confirmation payload (current password XOR OTP, per {@link
     *                  #confirmationMethod})
     * @param ip        client IP — audit
     * @param userAgent browser user-agent — audit
     * @return the effective deletion (anonymization) date
     * @throws ResponseStatusException 409 if a deletion is already pending, 403 if the
     *     confirmation (password or OTP) is missing or invalid
     */
    @Transactional
    public Instant requestDeletion(
            final User user, final AccountDeletionRequestDto req, final String ip, final String userAgent) {
        if (user.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, MSG_ALREADY_PENDING);
        }

        final DeletionConfirmationMethod method = confirmationMethod(user);
        if (method == DeletionConfirmationMethod.PASSWORD) {
            verifyPassword(user, req.currentPassword());
        } else {
            verifyOtp(user, req.otp());
        }

        final int graceDays = featureFlagRepo.getInt("ACCOUNT_DELETION_GRACE_DAYS", DEFAULT_GRACE_DAYS);
        final Instant now = Instant.now();
        final Instant effectiveAt = now.plus(graceDays, ChronoUnit.DAYS);

        // Immediate effects — revoke first: even if anything below failed, a half-applied
        // deletion must never leave stale sessions alive.
        tokenService.revokeAllForUser(user.getId());

        user.setDeletedAt(now);
        user.setScheduledDeletionAt(effectiveAt);
        userRepo.save(user);

        final String rawCancelToken = CryptoUtils.generateSecureToken();
        final AccountDeletionRequest request = new AccountDeletionRequest();
        request.setUser(user);
        request.setRequestedAt(now);
        request.setEffectiveAt(effectiveAt);
        request.setConfirmedVia(method);
        request.setCancelTokenHash(CryptoUtils.sha256(rawCancelToken));
        deletionRequestRepo.save(request);

        emailService.sendAccountDeletionConfirmationEmail(
            user.getEmail(), user.getFirstName(), effectiveAt, rawCancelToken, EmailService.toLocale(user.getLocale()));
        auditService.log(user, AuditService.ACCOUNT_DELETED, ip, userAgent);
        LOG.info("event=ACCOUNT_DELETION_REQUESTED userId={} effectiveAt={} method={}",
            user.getId(), effectiveAt, method);
        return effectiveAt;
    }

    /**
     * Cancels a pending deletion from the raw token embedded in the confirmation email's
     * cancellation link. Unauthenticated by design — every session was revoked when the
     * deletion was requested, so the link must work with no active PIVOT session.
     *
     * @param rawToken  raw cancellation token from the link
     * @param ip        client IP — audit
     * @param userAgent browser user-agent — audit
     * @throws ResponseStatusException 400 if the token is unknown or already cancelled, 410 if
     *     the account was already anonymized (too late to cancel)
     */
    @Transactional
    public void cancelDeletion(final String rawToken, final String ip, final String userAgent) {
        final AccountDeletionRequest request = deletionRequestRepo
            .findByCancelTokenHashAndCancelledAtIsNull(CryptoUtils.sha256(rawToken))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Lien d'annulation invalide ou déjà utilisé"));

        final User user = request.getUser();
        if (user.getAnonymizedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Ce compte a déjà été définitivement supprimé");
        }

        request.setCancelledAt(Instant.now());
        deletionRequestRepo.save(request);

        user.setDeletedAt(null);
        user.setScheduledDeletionAt(null);
        userRepo.save(user);

        emailService.sendAccountDeletionCancelledEmail(
            user.getEmail(), user.getFirstName(), EmailService.toLocale(user.getLocale()));
        auditService.log(user, AuditService.ACCOUNT_DELETION_CANCELLED, ip, userAgent);
        LOG.info("event=ACCOUNT_DELETION_CANCELLED userId={}", user.getId());
    }

    /**
     * Scheduled entry point ({@code AccountDeletionScheduler}): anonymizes every account whose
     * grace period has elapsed. Each account is processed in its own transaction ({@link
     * #anonymize(Long)} via {@link #self}) so one failure never rolls back the whole batch.
     */
    public void anonymizeDueAccounts() {
        final List<User> due =
            userRepo.findByDeletedAtIsNotNullAndAnonymizedAtIsNullAndScheduledDeletionAtBefore(Instant.now());
        if (due.isEmpty()) {
            return;
        }
        LOG.info("event=ACCOUNT_ANONYMIZE_BATCH_START count={}", due.size());
        for (final User user : due) {
            try {
                self.anonymize(user.getId());
            } catch (final RuntimeException e) {
                LOG.error("event=ACCOUNT_ANONYMIZE_FAILED userId={} error={}", user.getId(), e.getMessage());
            }
        }
        LOG.info("event=ACCOUNT_ANONYMIZE_BATCH_DONE count={}", due.size());
    }

    /**
     * Anonymizes a single account (RGPD Art. 17 purge): email replaced by
     * {@code deleted-{uuid}@pivot.invalid}, first/last name and avatar cleared (the avatar file
     * itself deleted via {@link AvatarStorageService}), remaining credential material (password
     * hash, Google id, OIDC subject) cleared. Sessions were already revoked when the deletion
     * was requested. Idempotent — a no-op if the user is gone or already anonymized, so a
     * concurrent scheduler run (e.g. after a restart) can never double-process a row.
     *
     * @param userId the account to anonymize
     */
    @Transactional
    public void anonymize(final Long userId) {
        final User user = userRepo.findById(userId).orElse(null);
        if (user == null || user.getAnonymizedAt() != null) {
            return;
        }

        avatarStorageService.deleteIfManaged(user.getAvatarUrl());

        user.setEmail("deleted-" + UUID.randomUUID() + "@pivot.invalid");
        user.setFirstName(null);
        user.setLastName(null);
        user.setAvatarUrl(null);
        user.setPasswordHash(null);
        user.setGoogleId(null);
        user.setOidcSubject(null);
        user.setAnonymizedAt(Instant.now());
        userRepo.save(user);

        // System action, not a client request — no ip/userAgent to attribute.
        auditService.log(user, AuditService.ACCOUNT_ANONYMIZED, null, null);
        LOG.info("event=ACCOUNT_ANONYMIZED userId={}", userId);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private void verifyPassword(final User user, final String currentPassword) {
        if (currentPassword == null || currentPassword.isBlank()
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            LOG.warn("event=ACCOUNT_DELETION_REJECTED reason=bad_current_password userId={}", user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MSG_CONFIRMATION_REJECTED);
        }
    }

    private void verifyOtp(final User user, final String otp) {
        if (otp == null || otp.isBlank()) {
            LOG.warn("event=ACCOUNT_DELETION_REJECTED reason=missing_otp userId={}", user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MSG_CONFIRMATION_REJECTED);
        }

        final AccountDeletionOtp pending = deletionOtpRepo
            .findFirstByUserIdAndConfirmedAtIsNullOrderByCreatedAtDesc(user.getId())
            .filter(o -> !o.isExpired())
            .orElse(null);
        if (pending == null) {
            LOG.warn("event=ACCOUNT_DELETION_REJECTED reason=no_pending_otp userId={}", user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MSG_CONFIRMATION_REJECTED);
        }
        if (pending.getAttempts() >= OTP_MAX_ATTEMPTS) {
            LOG.warn("event=ACCOUNT_DELETION_OTP_LOCKED userId={} attempts={}", user.getId(), pending.getAttempts());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trop de tentatives — demandez un nouveau code");
        }
        if (!pending.getOtpHash().equals(CryptoUtils.hmacSha256(otp, otpSecret))) {
            pending.setAttempts(pending.getAttempts() + 1);
            deletionOtpRepo.save(pending);
            LOG.warn("event=ACCOUNT_DELETION_REJECTED reason=bad_otp userId={}", user.getId());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, MSG_CONFIRMATION_REJECTED);
        }

        pending.setConfirmedAt(Instant.now());
        deletionOtpRepo.save(pending);
    }
}
