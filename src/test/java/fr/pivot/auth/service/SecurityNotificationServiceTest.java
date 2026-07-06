package fr.pivot.auth.service;

import fr.pivot.auth.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SecurityNotificationService} (US01.5.1) — the single entry point for
 * every "sensitive action confirmation" email: password changed, email changed, account
 * deletion requested, session(s) revoked. {@link EmailService} is mocked throughout, per the
 * US AC ("Tests TU SecurityNotificationService (mock EmailService)").
 *
 * <p>Traceability (US01.5.1 AC table):
 * <ul>
 *   <li>"Email envoyé après : changement mdp, changement email, suppression compte, révocation
 *       session" — {@code notifyPasswordChanged_*}, {@code notifyEmailChanged_*},
 *       {@code notifyAccountDeletionRequested_*}, {@code notifySessionsRevoked_*}</li>
 *   <li>"La révocation en masse ... génère un seul email récapitulatif, pas un email par
 *       session" — {@code notifySessionsRevoked_sendsOneEmail_regardlessOfCount},
 *       {@code notifySessionsRevoked_isNoOp_whenCountIsZeroOrNegative}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityNotificationServiceTest {

    @Mock private EmailService emailService;
    @Mock private User user;

    private SecurityNotificationService service;

    @BeforeEach
    void setUp() {
        service = new SecurityNotificationService(emailService);

        when(user.getEmail()).thenReturn("user@x.com");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getLocale()).thenReturn("fr");
    }

    // ---------------- notifyPasswordChanged ----------------

    @Test
    void notifyPasswordChanged_delegatesToEmailService_withUserFieldsAndLocale() {
        final Instant changedAt = Instant.now();

        service.notifyPasswordChanged(user, changedAt, "1.2.3.4");

        verify(emailService).sendPasswordChangedEmail(
            eq("user@x.com"), eq("Alice"), eq(changedAt), eq("1.2.3.4"), eq(Locale.FRENCH));
    }

    @Test
    void notifyPasswordChanged_resolvesEnglishLocale() {
        when(user.getLocale()).thenReturn("en");
        final Instant changedAt = Instant.now();

        service.notifyPasswordChanged(user, changedAt, "1.2.3.4");

        verify(emailService).sendPasswordChangedEmail(
            eq("user@x.com"), eq("Alice"), eq(changedAt), eq("1.2.3.4"), eq(Locale.ENGLISH));
    }

    // ---------------- notifyEmailChanged ----------------

    @Test
    void notifyEmailChanged_sendsToOldAddress_notNewOne() {
        final Instant changedAt = Instant.now();

        service.notifyEmailChanged(user, "old@x.com", "new@x.com", changedAt, "5.6.7.8");

        verify(emailService).sendEmailChangedNotificationEmail(
            eq("old@x.com"), eq("Alice"), eq("old@x.com"), eq("new@x.com"), eq(changedAt), eq("5.6.7.8"), eq(Locale.FRENCH));
    }

    // ---------------- notifyAccountDeletionRequested ----------------

    @Test
    void notifyAccountDeletionRequested_delegatesToEmailService() {
        final Instant effectiveAt = Instant.now().plusSeconds(2_592_000);

        service.notifyAccountDeletionRequested(user, effectiveAt, "raw-cancel-token", "9.9.9.9");

        verify(emailService).sendAccountDeletionConfirmationEmail(
            eq("user@x.com"), eq("Alice"), eq(effectiveAt), eq("raw-cancel-token"), eq("9.9.9.9"), eq(Locale.FRENCH));
    }

    // ---------------- notifySessionsRevoked ----------------

    @Test
    void notifySessionsRevoked_sendsOneEmail_regardlessOfCount() {
        final Instant revokedAt = Instant.now();

        service.notifySessionsRevoked(user, 5, revokedAt, "203.0.113.9");

        verify(emailService).sendSessionsRevokedEmail(
            eq("user@x.com"), eq("Alice"), eq(5), eq(revokedAt), eq("203.0.113.9"), eq(Locale.FRENCH));
    }

    @Test
    void notifySessionsRevoked_singleSession_sendsCountOfOne() {
        final Instant revokedAt = Instant.now();

        service.notifySessionsRevoked(user, 1, revokedAt, "203.0.113.9");

        verify(emailService).sendSessionsRevokedEmail(
            eq("user@x.com"), eq("Alice"), eq(1), eq(revokedAt), eq("203.0.113.9"), eq(Locale.FRENCH));
    }

    @Test
    void notifySessionsRevoked_isNoOp_whenCountIsZero() {
        service.notifySessionsRevoked(user, 0, Instant.now(), "203.0.113.9");

        verifyNoInteractions(emailService);
    }

    @Test
    void notifySessionsRevoked_isNoOp_whenCountIsNegative() {
        service.notifySessionsRevoked(user, -1, Instant.now(), "203.0.113.9");

        verify(emailService, never()).sendSessionsRevokedEmail(any(), any(), anyInt(), any(), any(), any());
    }
}
