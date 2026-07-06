package fr.pivot.auth.service;

import fr.pivot.auth.exception.EmailDeliveryException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

@Service
public class EmailService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);
    private static final String KEY_FIRST_NAME = "firstName";
    private static final String KEY_RESET_URL = "resetUrl";
    private static final String KEY_LOGIN_URL = "loginUrl";
    private static final String KEY_SECURE_ACCOUNT_URL = "secureAccountUrl";
    private static final String KEY_IP = "ip";
    private static final String PATH_LOGIN = "/auth/login";
    private static final String PATH_FORGOT_PASSWORD = "/auth/forgot-password";

    /**
     * Target of the "Not me" call-to-action carried by every sensitive-action security
     * notification (US01.5.1) — password changed, email changed, sessions revoked. Lands on the
     * account-security screen with a query param the frontend uses to open the "report
     * suspicious activity" panel directly, rather than the generic forgot-password page these
     * emails pointed to before this US.
     */
    private static final String PATH_ACCOUNT_SECURITY = "/account/security?action=report-suspicious";
    private static final String DATE_FORMAT_KEY = "email.password-changed.date-format";
    private static final String UNKNOWN_IP_KEY = "email.password-changed.unknown-ip";

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final MessageSource messageSource;
    private final String from;
    private final String appUrl;
    private final String supportEmail;

    public EmailService(
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            MessageSource messageSource,
            @Value("${pivot.mail.from:noreply@pivot.app}") String from,
            @Value("${pivot.app.url:http://localhost:4200}") String appUrl,
            @Value("${pivot.app.support-email:support@pivot.app}") String supportEmail) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.messageSource = messageSource;
        this.from = from;
        this.appUrl = appUrl;
        this.supportEmail = supportEmail;
    }

    /** Converts a supported language tag ({@code "fr"}, {@code "en"}) to a {@link Locale}, defaulting to French. */
    public static Locale toLocale(String localeTag) {
        if (localeTag == null || localeTag.isBlank()) {
            return Locale.FRENCH;
        }
        return "en".equals(localeTag) ? Locale.ENGLISH : Locale.FRENCH;
    }

    @Async
    public void sendVerificationEmail(String to, String firstName, String token, Locale locale) {
        send(to, subject("email.subject.verify-email", locale),
            "email/verify-email",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "verifyUrl", appUrl + "/auth/verify-email?token=" + token),
            locale);
    }

    @Async
    public void sendPasswordResetEmail(String to, String firstName, String token, Locale locale) {
        send(to, subject("email.subject.reset-password", locale),
            "email/reset-password",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   KEY_RESET_URL, appUrl + "/auth/reset-password?token=" + token),
            locale);
    }

    @Async
    public void sendDeviceVerifyEmail(String to, String firstName, String otp, String deviceName, Locale locale) {
        send(to, subject("email.subject.device-confirm", locale),
            "email/device-confirm",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "otp", otp,
                   "deviceName", deviceName != null ? deviceName
                       : messageSource.getMessage("email.device-confirm.unknown-device", null, locale)),
            locale);
    }

    /**
     * Notifies an address that a registration was attempted but an account already exists.
     * Sent instead of returning 409 to avoid email enumeration (RGPD Art. 5.1c).
     *
     * @param to        the existing account's email address
     * @param firstName the account holder's first name (may be {@code null})
     * @param locale    the recipient's preferred locale
     */
    @Async
    public void sendAccountExistsEmail(String to, String firstName, Locale locale) {
        send(to, subject("email.subject.account-exists", locale),
            "email/account-exists",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   KEY_LOGIN_URL, appUrl + PATH_LOGIN,
                   KEY_RESET_URL, appUrl + PATH_FORGOT_PASSWORD),
            locale);
    }

    /**
     * Notifies an address that an unverified account already exists.
     * Provides a fresh verification link without revealing whether this is a duplicate attempt.
     *
     * @param to        the existing unverified account's email address
     * @param firstName the account holder's first name (may be {@code null})
     * @param token     the new raw verification token to embed in the link
     * @param locale    the recipient's preferred locale
     */
    @Async
    public void sendVerificationReminderEmail(String to, String firstName, String token, Locale locale) {
        send(to, subject("email.subject.verify-reminder", locale),
            "email/verify-reminder",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "verifyUrl", appUrl + "/auth/verify-email?token=" + token),
            locale);
    }

    /**
     * Security notification sent after a successful password reset.
     * Includes the timestamp and IP so the user can detect unauthorized changes.
     *
     * @param locale the recipient's preferred locale
     */
    @Async
    public void sendPasswordChangedEmail(String to, String firstName, Instant changedAt, String ip, Locale locale) {
        final String pattern = messageSource.getMessage(DATE_FORMAT_KEY, null, locale);
        final String formattedDate = buildDateFormatter(pattern).format(changedAt);
        send(to, subject("email.subject.password-changed", locale),
            "email/password-changed",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "changedAt", formattedDate,
                   KEY_IP, resolveIp(ip, locale),
                   KEY_SECURE_ACCOUNT_URL, secureAccountUrl()),
            locale);
    }

    /**
     * Confirmation-link email sent to the CANDIDATE new address for a "change my email"
     * request (US02.2.2). The current address is never touched until this link is clicked —
     * sent only to the new one, never to the old one.
     *
     * @param to        the candidate new address
     * @param firstName the account holder's first name (may be {@code null})
     * @param token     the raw confirmation token to embed in the link (SHA-256-hashed in DB)
     * @param locale    the account holder's preferred locale
     */
    @Async
    public void sendEmailChangeConfirmationEmail(String to, String firstName, String token, Locale locale) {
        send(to, subject("email.subject.email-change-confirm", locale),
            "email/email-change-confirm",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "confirmUrl", appUrl + "/account/email/confirm?token=" + token),
            locale);
    }

    /**
     * Security notification sent to the OLD address once an email change has been confirmed.
     * Includes both addresses, the timestamp and the IP so the user can detect an
     * unauthorized change even though they no longer receive mail at the new address.
     *
     * @param to        the OLD (now inactive) address — the only place this notice can reach
     *                  the legitimate owner if the change was not authorized
     * @param firstName the account holder's first name (may be {@code null})
     * @param oldEmail  the address being replaced, repeated in the body for clarity
     * @param newEmail  the newly confirmed address
     * @param changedAt when the change was confirmed
     * @param ip        client IP of the confirmation request (may be {@code null})
     * @param locale    the account holder's preferred locale
     */
    @Async
    public void sendEmailChangedNotificationEmail(
            String to, String firstName, String oldEmail, String newEmail, Instant changedAt, String ip, Locale locale) {
        final String pattern = messageSource.getMessage(DATE_FORMAT_KEY, null, locale);
        final String formattedDate = buildDateFormatter(pattern).format(changedAt);
        send(to, subject("email.subject.email-changed", locale),
            "email/email-changed",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "oldEmail", oldEmail,
                   "newEmail", newEmail,
                   "changedAt", formattedDate,
                   KEY_IP, resolveIp(ip, locale),
                   KEY_SECURE_ACCOUNT_URL, secureAccountUrl()),
            locale);
    }

    /**
     * Notifies an address that a "change my email" request targeted it as the new address,
     * but it already has a PIVOT account. Sent instead of a 409 to the requester, so the
     * initiation endpoint never reveals account existence (RGPD Art. 5.1c / no enumeration) —
     * mirrors {@link #sendAccountExistsEmail} for the registration flow.
     *
     * @param to        the existing account's email address (the attempted new address)
     * @param firstName the existing account holder's first name (may be {@code null})
     * @param locale    the existing account holder's preferred locale
     */
    @Async
    public void sendEmailChangeDuplicateEmail(String to, String firstName, Locale locale) {
        send(to, subject("email.subject.email-change-duplicate", locale),
            "email/email-change-duplicate",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   KEY_LOGIN_URL, appUrl + PATH_LOGIN,
                   KEY_RESET_URL, appUrl + PATH_FORGOT_PASSWORD),
            locale);
    }

    /**
     * Notifies a user that a tenant admin has just reactivated their account (US06.1.5 « Admin
     * réactive un compte utilisateur »). Sent only when the account genuinely transitions from
     * {@code INACTIVE} to {@code ACTIVE} — see {@code AdminUserService#updateStatus} — so a
     * retried/idempotent reactivation of an already-{@code ACTIVE} account never re-sends it.
     *
     * @param to        the reactivated account's email address
     * @param firstName the account holder's first name (may be {@code null})
     * @param locale    the account holder's preferred locale
     */
    @Async
    public void sendAccountReactivatedEmail(String to, String firstName, Locale locale) {
        send(to, subject("email.subject.account-reactivated", locale),
            "email/account-reactivated",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   KEY_LOGIN_URL, appUrl + PATH_LOGIN),
            locale);
    }

    @Async
    public void sendWelcomeEmail(String to, String firstName, Locale locale) {
        send(to, subject("email.subject.welcome", locale),
            "email/welcome",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "appUrl", appUrl,
                   "docsUrl", appUrl + "/docs"),
            locale);
    }

    /**
     * Confirmation sent to the person who submitted the contact form.
     *
     * @param to      sender's email address
     * @param message the original message for reference
     * @param locale  the sender's preferred locale
     */
    @Async
    public void sendContactConfirmation(String to, String message, Locale locale) {
        send(to, subject("email.subject.contact-confirmation", locale),
            "email/contact-confirmation",
            Map.of("message", message),
            locale);
    }

    /**
     * RGPD Art. 20 — notifies the user that their personal-data export archive is ready
     * for download (US02.3.1). The link points to an authenticated frontend page — never a
     * public presigned URL — that in turn calls {@code GET /api/account/export/download/{token}}.
     *
     * @param to          the export owner's email address
     * @param firstName   the account holder's first name (may be {@code null})
     * @param downloadToken the raw one-time download token to embed in the link
     * @param locale      the recipient's preferred locale
     */
    @Async
    public void sendExportReadyEmail(String to, String firstName, String downloadToken, Locale locale) {
        send(to, subject("email.subject.export-ready", locale),
            "email/export-ready",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "downloadUrl", appUrl + "/account/export/download?token=" + downloadToken),
            locale);
    }

    /**
     * RGPD Art. 20 — notifies the user that their personal-data export request failed
     * (US02.3.1), so they are not left waiting indefinitely for a download link that will
     * never arrive. Invites them to retry from their account page.
     *
     * @param to        the export owner's email address
     * @param firstName the account holder's first name (may be {@code null})
     * @param locale    the recipient's preferred locale
     */
    @Async
    public void sendExportFailedEmail(String to, String firstName, Locale locale) {
        send(to, subject("email.subject.export-failed", locale),
            "email/export-failed",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale)),
            locale);
    }

    /**
     * RGPD Art. 17 — confirms an account-deletion request immediately after it is confirmed
     * (US02.2.4). States the effective purge date and carries the single-use cancellation link
     * (the only way to abort the deletion once tokens have already been revoked).
     *
     * @param to           the account's email address (not yet anonymized at this point)
     * @param firstName    the account holder's first name (may be {@code null})
     * @param effectiveAt  the instant the grace period elapses and anonymization runs
     * @param cancelToken  raw single-use cancellation token (SHA-256-hashed in DB)
     * @param ip           client IP of the deletion request (may be {@code null}) — US01.5.1
     * @param locale       the account holder's preferred locale
     */
    @Async
    public void sendAccountDeletionConfirmationEmail(
            String to, String firstName, Instant effectiveAt, String cancelToken, String ip, Locale locale) {
        final String pattern = messageSource.getMessage("email.account-deletion.date-format", null, locale);
        final String formattedDate = buildDateFormatter(pattern).format(effectiveAt);
        send(to, subject("email.subject.account-deletion-confirm", locale),
            "email/account-deletion-confirm",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "effectiveDate", formattedDate,
                   KEY_IP, resolveIp(ip, locale),
                   "cancelUrl", appUrl + "/account/deletion/cancel?token=" + cancelToken),
            locale);
    }

    /**
     * Emails the 6-digit OTP confirming an account-deletion request for an account with no
     * local password (auth_mode OIDC / Google-only) — US02.2.4.
     *
     * @param to        the account's email address
     * @param firstName the account holder's first name (may be {@code null})
     * @param otp       the 6-digit one-time code (TTL {@code ACCOUNT_DELETION_OTP_TTL_MINUTES})
     * @param locale    the account holder's preferred locale
     */
    @Async
    public void sendAccountDeletionOtpEmail(String to, String firstName, String otp, Locale locale) {
        send(to, subject("email.subject.account-deletion-otp", locale),
            "email/account-deletion-otp",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale), "otp", otp),
            locale);
    }

    /**
     * Confirms that a pending account deletion was cancelled before the grace period elapsed
     * (US02.2.4) — the account is immediately usable again (a fresh login is still required,
     * every session was revoked when the deletion was requested).
     *
     * @param to        the account's email address
     * @param firstName the account holder's first name (may be {@code null})
     * @param locale    the account holder's preferred locale
     */
    @Async
    public void sendAccountDeletionCancelledEmail(String to, String firstName, Locale locale) {
        send(to, subject("email.subject.account-deletion-cancelled", locale),
            "email/account-deletion-cancelled",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   KEY_LOGIN_URL, appUrl + PATH_LOGIN),
            locale);
    }

    /**
     * Passive suspicious-login alert (US01.4.3a) — sent when a login succeeds from a device
     * unknown to {@code trusted_devices} while the US01.4.1 device-OTP gate did not apply. Never
     * blocks the login; carries the "Not me" single-use link (TTL 1h) that redirects to a full
     * re-authentication page (current password required) rather than directly revoking anything.
     *
     * @param to         the account's email address
     * @param firstName  the account holder's first name (may be {@code null})
     * @param deviceName human-readable device label, or {@code null} if not provided
     * @param loginAt    when the flagged login occurred
     * @param notMeToken raw single-use "Not me" token to embed in the link (SHA-256-hashed in DB)
     * @param locale     the account holder's preferred locale
     */
    @Async
    public void sendSuspiciousLoginAlertEmail(
            String to, String firstName, String deviceName, Instant loginAt, String notMeToken, Locale locale) {
        final String pattern = messageSource.getMessage("email.password-changed.date-format", null, locale);
        final String formattedDate = buildDateFormatter(pattern).format(loginAt);
        send(to, subject("email.subject.suspicious-login", locale),
            "email/suspicious-login",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "deviceName", deviceName != null ? deviceName
                       : messageSource.getMessage("email.device-confirm.unknown-device", null, locale),
                   "loginAt", formattedDate,
                   "notMeUrl", appUrl + "/auth/suspicious-login/confirm?token=" + notMeToken),
            locale);
    }

    /**
     * Security notification sent after one or more of the user's active sessions were revoked
     * (US01.5.1) — whether the user themselves signed out a single other device from the active
     * sessions screen, or every other session was revoked at once. Always a single email per
     * revocation call, regardless of how many sessions it covered — a bulk revocation (e.g.
     * {@code DELETE /api/account/sessions}) never fans out into one email per session.
     *
     * @param to           the account's email address
     * @param firstName    the account holder's first name (may be {@code null})
     * @param revokedCount number of sessions revoked by this call (always &ge; 1)
     * @param revokedAt    when the revocation happened
     * @param ip           client IP of the request that triggered the revocation (may be
     *                     {@code null}) — this is the actor's IP, which matters precisely
     *                     because that actor might not be the legitimate account owner
     * @param locale       the account holder's preferred locale
     */
    @Async
    public void sendSessionsRevokedEmail(
            String to, String firstName, int revokedCount, Instant revokedAt, String ip, Locale locale) {
        final String pattern = messageSource.getMessage(DATE_FORMAT_KEY, null, locale);
        final String formattedDate = buildDateFormatter(pattern).format(revokedAt);
        send(to, subject("email.subject.sessions-revoked", locale),
            "email/sessions-revoked",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "revokedCount", revokedCount,
                   "revokedAt", formattedDate,
                   KEY_IP, resolveIp(ip, locale),
                   KEY_SECURE_ACCOUNT_URL, secureAccountUrl()),
            locale);
    }

    /**
     * Internal notification forwarded to the owner — Reply-To set to the sender's address
     * so the owner can reply directly to the user.
     *
     * @param to      owner email
     * @param from    the sender's email from the form (also used as Reply-To)
     * @param message the message body
     * @param locale  the sender's preferred locale (used as notification context)
     */
    @Async
    public void sendContactNotification(String to, String from, String message, Locale locale) {
        send(to, subject("email.subject.contact-notification", locale),
            "email/contact-notification",
            Map.of("from", from, "message", message),
            locale,
            from);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private String subject(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }

    /** Returns a formatter for {@code pattern}, falling back to ISO-8601 if the pattern is invalid. */
    private DateTimeFormatter buildDateFormatter(final String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.of("UTC"));
        } catch (final IllegalArgumentException _) {
            LOG.warn("Invalid date format pattern '{}' in message bundle, falling back to ISO-8601", pattern);
            return DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));
        }
    }

    private String fallback(Locale locale) {
        return messageSource.getMessage("email.common.fallback-name", null, locale);
    }

    /** Returns {@code ip}, or the localized "unknown" placeholder when it is {@code null}. */
    private String resolveIp(final String ip, final Locale locale) {
        return ip != null ? ip : messageSource.getMessage(UNKNOWN_IP_KEY, null, locale);
    }

    /** Absolute URL of the account-security "report suspicious activity" landing page. */
    private String secureAccountUrl() {
        return appUrl + PATH_ACCOUNT_SECURITY;
    }

    private void send(String to, String subject, String template, Map<String, Object> vars, Locale locale) {
        send(to, subject, template, vars, locale, null);
    }

    private void send(String to, String subject, String template, Map<String, Object> vars, Locale locale, String replyTo) {
        try {
            final Context ctx = new Context(locale);
            vars.forEach(ctx::setVariable);
            ctx.setVariable("appUrl", appUrl);
            ctx.setVariable("supportEmail", supportEmail);
            final String html = templateEngine.process(template, ctx);

            final MimeMessage msg = mailSender.createMimeMessage();
            final MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
        } catch (MessagingException e) {
            throw new EmailDeliveryException("Email send failed: " + e.getMessage(), e);
        }
    }
}
