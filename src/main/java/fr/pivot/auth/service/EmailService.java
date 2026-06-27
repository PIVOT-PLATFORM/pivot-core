package fr.pivot.auth.service;

import fr.pivot.auth.exception.EmailDeliveryException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
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

    private static final String KEY_FIRST_NAME = "firstName";
    private static final String KEY_RESET_URL = "resetUrl";

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String from;
    private final String appUrl;
    private final String supportEmail;

    public EmailService(
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            @Value("${pivot.mail.from:noreply@pivot.app}") String from,
            @Value("${pivot.app.url:http://localhost:4200}") String appUrl,
            @Value("${pivot.app.support-email:support@pivot.app}") String supportEmail) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.from = from;
        this.appUrl = appUrl;
        this.supportEmail = supportEmail;
    }

    @Async
    public void sendVerificationEmail(String to, String firstName, String token) {
        send(to, "Confirmez votre adresse email — PIVOT",
            "email/verify-email",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : "là",
                   "verifyUrl", appUrl + "/auth/verify-email?token=" + token));
    }

    @Async
    public void sendPasswordResetEmail(String to, String firstName, String token) {
        send(to, "Réinitialisation de votre mot de passe — PIVOT",
            "email/reset-password",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : "là",
                   KEY_RESET_URL, appUrl + "/auth/reset-password?token=" + token));
    }

    @Async
    public void sendDeviceVerifyEmail(String to, String firstName, String otp, String deviceName) {
        send(to, "Connexion depuis un nouvel appareil — PIVOT",
            "email/device-confirm",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : "là",
                   "otp", otp,
                   "deviceName", deviceName != null ? deviceName : "appareil inconnu"));
    }

    /**
     * Notifies an address that a registration was attempted but an account already exists.
     * Sent instead of returning a 409 to the client, to avoid email enumeration (RGPD).
     *
     * @param to        the existing account's email address
     * @param firstName the account holder's first name (may be {@code null})
     */
    @Async
    public void sendAccountExistsEmail(String to, String firstName) {
        send(to, "Vous avez déjà un compte — PIVOT",
            "email/account-exists",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : "là",
                   "loginUrl", appUrl + "/auth/login",
                   KEY_RESET_URL, appUrl + "/auth/forgot-password"));
    }

    /**
     * Notifies an address that a registration was attempted but an unverified account already exists.
     * Provides a fresh verification link without revealing whether this is a duplicate attempt.
     *
     * @param to        the existing unverified account's email address
     * @param firstName the account holder's first name (may be {@code null})
     * @param token     the new raw verification token to embed in the link
     */
    @Async
    public void sendVerificationReminderEmail(String to, String firstName, String token) {
        send(to, "Rappel — vérification de votre compte PIVOT",
            "email/verify-reminder",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : "là",
                   "verifyUrl", appUrl + "/auth/verify-email?token=" + token));
    }

    /**
     * Security notification sent after a successful password reset.
     * Includes the timestamp and IP so the user can detect unauthorized changes.
     */
    @Async
    public void sendPasswordChangedEmail(String to, String firstName, Instant changedAt, String ip) {
        final String formattedDate = DateTimeFormatter
            .ofPattern("dd/MM/yyyy 'à' HH:mm 'UTC'")
            .withZone(ZoneId.of("UTC"))
            .format(changedAt);
        send(to, "Votre mot de passe PIVOT a été modifié",
            "email/password-changed",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : "là",
                   "changedAt", formattedDate,
                   "ip", ip != null ? ip : "inconnue",
                   KEY_RESET_URL, appUrl + "/auth/forgot-password"));
    }

    @Async
    public void sendWelcomeEmail(String to, String firstName) {
        send(to, "Bienvenue sur PIVOT !",
            "email/welcome",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : "là",
                   "appUrl", appUrl));
    }

    private void send(String to, String subject, String template, Map<String, Object> vars) {
        try {
            Context ctx = new Context(Locale.FRENCH);
            vars.forEach(ctx::setVariable);
            ctx.setVariable("appUrl", appUrl);
            ctx.setVariable("supportEmail", supportEmail);
            String html = templateEngine.process(template, ctx);

            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
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
