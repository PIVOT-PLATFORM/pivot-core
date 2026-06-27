package fr.pivot.auth.service;

import fr.pivot.auth.exception.EmailDeliveryException;
import jakarta.mail.MessagingException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

    @Test
    void sendAccountExistsEmail_rendersAndSends() {
        service.sendAccountExistsEmail("user@x.com", "Eve");

        verify(templateEngine).process(eq("email/account-exists"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendAccountExistsEmail_handlesNullFirstName() {
        service.sendAccountExistsEmail("user@x.com", null);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendVerificationReminderEmail_rendersAndSends() {
        service.sendVerificationReminderEmail("user@x.com", "Frank", "remind-tok");

        verify(templateEngine).process(eq("email/verify-reminder"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendPasswordChangedEmail_rendersAndSends() {
        service.sendPasswordChangedEmail("user@x.com", "Grace", java.time.Instant.now(), "1.2.3.4");

        verify(templateEngine).process(eq("email/password-changed"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendPasswordChangedEmail_handlesNullFirstNameAndIp() {
        service.sendPasswordChangedEmail("user@x.com", null, java.time.Instant.now(), null);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void send_throwsEmailDeliveryException_onMessagingException() throws Exception {
        final MimeMessage broken = mock(MimeMessage.class);
        doThrow(new MessagingException("SMTP failure"))
            .when(broken).setSubject(any(String.class), any(String.class));
        when(mailSender.createMimeMessage()).thenReturn(broken);

        assertThatThrownBy(() -> service.sendWelcomeEmail("x@x.com", "X"))
            .isInstanceOf(EmailDeliveryException.class);
    }
}
