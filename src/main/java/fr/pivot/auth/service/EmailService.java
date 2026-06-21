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

import java.util.Locale;
import java.util.Map;

@Service
public class EmailService {

    private static final String KEY_FIRST_NAME = "firstName";

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String from;
    private final String appUrl;

    public EmailService(
            JavaMailSender mailSender,
            TemplateEngine templateEngine,
            @Value("${pivot.mail.from:noreply@pivot.app}") String from,
            @Value("${pivot.app.url:http://localhost:4200}") String appUrl) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.from = from;
        this.appUrl = appUrl;
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
                   "resetUrl", appUrl + "/auth/reset-password?token=" + token));
    }

    @Async
    public void sendDeviceVerifyEmail(String to, String firstName, String otp, String deviceName) {
        send(to, "Connexion depuis un nouvel appareil — PIVOT",
            "email/device-confirm",
            Map.of(KEY_FIRST_NAME, firstName != null ? firstName : "là",
                   "otp", otp,
                   "deviceName", deviceName != null ? deviceName : "appareil inconnu"));
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
