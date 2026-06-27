package fr.pivot.auth.service;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailService} — verifies template rendering + message dispatch
 * for each transactional email, including null-firstName fallback branches.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailServiceTest {

    @Mock private JavaMailSender mailSender;
    @Mock private TemplateEngine templateEngine;

    private EmailService service;

    @BeforeEach
    void setUp() {
        service = new EmailService(mailSender, templateEngine, "noreply@pivot.app", "http://app", "support@pivot.app");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
        when(templateEngine.process(any(String.class), any(Context.class))).thenReturn("<html>body</html>");
    }

    @Test
    void sendVerificationEmail_rendersAndSends() {
        service.sendVerificationEmail("user@x.com", "Alice", "tok123");

        verify(templateEngine).process(eq("email/verify-email"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendVerificationEmail_handlesNullFirstName() {
        service.sendVerificationEmail("user@x.com", null, "tok123");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendPasswordResetEmail_rendersAndSends() {
        service.sendPasswordResetEmail("user@x.com", "Bob", "reset-tok");

        verify(templateEngine).process(eq("email/reset-password"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendDeviceVerifyEmail_rendersAndSends() {
        service.sendDeviceVerifyEmail("user@x.com", "Carol", "999111", "Chrome");

        verify(templateEngine).process(eq("email/device-confirm"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendDeviceVerifyEmail_handlesNullDeviceName() {
        service.sendDeviceVerifyEmail("user@x.com", "Carol", "999111", null);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendWelcomeEmail_rendersAndSends() {
        service.sendWelcomeEmail("user@x.com", "Dave");

        verify(templateEngine).process(eq("email/welcome"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }
}
