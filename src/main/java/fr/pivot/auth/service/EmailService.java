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
                   "loginUrl", appUrl + "/auth/login",
                   KEY_RESET_URL, appUrl + "/auth/forgot-password"),
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
        final String pattern = messageSource.getMessage("email.password-changed.date-format", null, locale);
        final String formattedDate = buildDateFormatter(pattern).format(changedAt);
        send(to, subject("email.subject.password-changed", locale),
            "email/password-changed",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "changedAt", formattedDate,
                   "ip", ip != null ? ip
                       : messageSource.getMessage("email.password-changed.unknown-ip", null, locale),
                   KEY_RESET_URL, appUrl + "/auth/forgot-password"),
            locale);
    }

    @Async
    public void sendWelcomeEmail(String to, String firstName, Locale locale) {
        send(to, subject("email.subject.welcome", locale),
            "email/welcome",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : fallback(locale),
                   "appUrl", appUrl),
            locale);
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

    private void send(String to, String subject, String template, Map<String, Object> vars, Locale locale) {
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
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
        } catch (MessagingException e) {
            throw new EmailDeliveryException("Email send failed: " + e.getMessage(), e);
        }
    }
}
