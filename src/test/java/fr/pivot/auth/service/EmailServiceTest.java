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
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock private MessageSource messageSource;

    private EmailService service;

    @BeforeEach
    void setUp() {
        service = new EmailService(mailSender, templateEngine, messageSource,
            "noreply@pivot.app", "http://app", "support@pivot.app");
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
        when(templateEngine.process(any(String.class), any(Context.class))).thenReturn("<html>body</html>");
        when(messageSource.getMessage(any(String.class), any(), any(Locale.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(messageSource.getMessage(eq("email.password-changed.date-format"), any(), any(Locale.class)))
            .thenReturn("dd/MM/yyyy HH:mm");
    }

    @Test
    void sendVerificationEmail_rendersAndSends() {
        service.sendVerificationEmail("user@x.com", "Alice", "tok123", Locale.FRENCH);

        verify(templateEngine).process(eq("email/verify-email"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendVerificationEmail_handlesNullFirstName() {
        service.sendVerificationEmail("user@x.com", null, "tok123", Locale.FRENCH);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendPasswordResetEmail_rendersAndSends() {
        service.sendPasswordResetEmail("user@x.com", "Bob", "reset-tok", Locale.FRENCH);

        verify(templateEngine).process(eq("email/reset-password"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendDeviceVerifyEmail_rendersAndSends() {
        service.sendDeviceVerifyEmail("user@x.com", "Carol", "999111", "Chrome", Locale.FRENCH);

        verify(templateEngine).process(eq("email/device-confirm"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendDeviceVerifyEmail_handlesNullDeviceName() {
        service.sendDeviceVerifyEmail("user@x.com", "Carol", "999111", null, Locale.FRENCH);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendWelcomeEmail_rendersAndSends() {
        service.sendWelcomeEmail("user@x.com", "Dave", Locale.FRENCH);

        verify(templateEngine).process(eq("email/welcome"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendAccountExistsEmail_rendersAndSends() {
        service.sendAccountExistsEmail("user@x.com", "Eve", Locale.FRENCH);

        verify(templateEngine).process(eq("email/account-exists"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendAccountExistsEmail_handlesNullFirstName() {
        service.sendAccountExistsEmail("user@x.com", null, Locale.FRENCH);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendAccountReactivatedEmail_rendersAndSends() {
        service.sendAccountReactivatedEmail("user@x.com", "Hugo", Locale.FRENCH);

        verify(templateEngine).process(eq("email/account-reactivated"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendAccountReactivatedEmail_handlesNullFirstName() {
        service.sendAccountReactivatedEmail("user@x.com", null, Locale.FRENCH);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendVerificationReminderEmail_rendersAndSends() {
        service.sendVerificationReminderEmail("user@x.com", "Frank", "remind-tok", Locale.FRENCH);

        verify(templateEngine).process(eq("email/verify-reminder"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendPasswordChangedEmail_rendersAndSends() {
        service.sendPasswordChangedEmail("user@x.com", "Grace", java.time.Instant.now(), "1.2.3.4", Locale.FRENCH);

        verify(templateEngine).process(eq("email/password-changed"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendPasswordChangedEmail_handlesNullFirstNameAndIp() {
        service.sendPasswordChangedEmail("user@x.com", null, java.time.Instant.now(), null, Locale.FRENCH);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendSuspiciousLoginAlertEmail_rendersAndSends() {
        service.sendSuspiciousLoginAlertEmail(
            "user@x.com", "Ivan", "Chrome · Windows", java.time.Instant.now(), "not-me-tok", Locale.FRENCH);

        verify(templateEngine).process(eq("email/suspicious-login"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendSuspiciousLoginAlertEmail_handlesNullFirstNameAndDeviceName() {
        service.sendSuspiciousLoginAlertEmail(
            "user@x.com", null, null, java.time.Instant.now(), "not-me-tok", Locale.FRENCH);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void send_throwsEmailDeliveryException_onMessagingException() throws Exception {
        final MimeMessage broken = mock(MimeMessage.class);
        doThrow(new MessagingException("SMTP failure"))
            .when(broken).setSubject(any(String.class), any(String.class));
        when(mailSender.createMimeMessage()).thenReturn(broken);

        assertThatThrownBy(() -> service.sendWelcomeEmail("x@x.com", "X", Locale.FRENCH))
            .isInstanceOf(EmailDeliveryException.class);
    }

    // ----------------------------------------------------------------
    // toLocale
    // ----------------------------------------------------------------

    @Test
    void toLocale_returns_french_for_fr() {
        assertThat(EmailService.toLocale("fr")).isEqualTo(Locale.FRENCH);
    }

    @Test
    void toLocale_returns_english_for_en() {
        assertThat(EmailService.toLocale("en")).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void toLocale_returns_french_for_null() {
        assertThat(EmailService.toLocale(null)).isEqualTo(Locale.FRENCH);
    }

    @Test
    void toLocale_returns_french_for_blank() {
        assertThat(EmailService.toLocale("  ")).isEqualTo(Locale.FRENCH);
    }

    @Test
    void toLocale_returns_french_for_unknown_lang() {
        assertThat(EmailService.toLocale("de")).isEqualTo(Locale.FRENCH);
    }

    // ----------------------------------------------------------------
    // sendContactConfirmation
    // ----------------------------------------------------------------

    @Test
    void sendContactConfirmation_rendersAndSends_in_french() {
        service.sendContactConfirmation("user@x.com", "Mon message", Locale.FRENCH);

        verify(templateEngine).process(eq("email/contact-confirmation"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendContactConfirmation_rendersAndSends_in_english() {
        service.sendContactConfirmation("user@x.com", "My message", Locale.ENGLISH);

        verify(templateEngine).process(eq("email/contact-confirmation"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    // ----------------------------------------------------------------
    // sendContactNotification
    // ----------------------------------------------------------------

    @Test
    void sendContactNotification_rendersAndSends_with_replyTo() {
        service.sendContactNotification("owner@pivot.app", "user@x.com", "Une question", Locale.FRENCH);

        verify(templateEngine).process(eq("email/contact-notification"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendContactNotification_rendersAndSends_in_english() {
        service.sendContactNotification("owner@pivot.app", "user@x.com", "A question", Locale.ENGLISH);

        verify(templateEngine).process(eq("email/contact-notification"), any(Context.class));
        verify(mailSender).send(any(MimeMessage.class));
    }

    // ----------------------------------------------------------------
    // buildDateFormatter — fallback branch
    // ----------------------------------------------------------------

    @Test
    void sendPasswordChangedEmail_fallsBackToIso_on_invalid_date_format_pattern() {
        when(messageSource.getMessage(eq("email.password-changed.date-format"), any(), any(Locale.class)))
            .thenReturn("INVALID_PATTERN_!!!");

        service.sendPasswordChangedEmail("user@x.com", "Grace", java.time.Instant.now(), "1.2.3.4", Locale.FRENCH);

        verify(mailSender).send(any(MimeMessage.class));
    }
}
