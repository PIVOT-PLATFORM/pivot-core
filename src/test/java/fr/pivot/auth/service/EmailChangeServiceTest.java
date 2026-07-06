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
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailChangeService} — the "change my email" request/confirm flow
 * (US02.2.2): rate limiting, current-password verification, anti-enumeration on duplicate
 * targets, request cancellation and the single-use confirmation token lifecycle.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailChangeServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long TENANT_ID = 1L;
    private static final String NEW_EMAIL = "new@x.com";
    private static final String CURRENT_PASSWORD = "CurrentPass1!";

    @Mock private UserRepository userRepo;
    @Mock private EmailChangeRequestRepository emailChangeRepo;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private SecurityNotificationService securityNotificationService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private AuditService auditService;
    @Mock private User user;
    @Mock private Tenant tenant;

    private EmailChangeService service;

    @BeforeEach
    void setUp() {
        service = new EmailChangeService(
            userRepo, emailChangeRepo, passwordEncoder, emailService, securityNotificationService,
            rateLimiter, auditService, 24L);

        when(rateLimiter.emailChangeUserBucket(anyString())).thenReturn("email-change:user:7");
        when(rateLimiter.emailChangeConfirmIpBucket(anyString())).thenReturn("email-change-confirm:ip:ip");
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);

        when(user.getId()).thenReturn(USER_ID);
        when(user.getEmail()).thenReturn("old@x.com");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getPasswordHash()).thenReturn("hashed-current-password");
        when(user.getTenant()).thenReturn(tenant);
        when(tenant.getId()).thenReturn(TENANT_ID);

        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hashed-current-password")).thenReturn(true);
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(TENANT_ID, NEW_EMAIL)).thenReturn(Optional.empty());
        when(userRepo.existsByTenantIdAndEmailAndDeletedAtIsNull(TENANT_ID, NEW_EMAIL)).thenReturn(false);
    }

    private ChangeEmailRequest req() {
        return new ChangeEmailRequest(NEW_EMAIL, CURRENT_PASSWORD);
    }

    // ---------------- requestEmailChange ----------------

    @Test
    void requestEmailChange_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(false);

        final ChangeEmailRequest r = req();
        assertThatThrownBy(() -> service.requestEmailChange(USER_ID, r, "ip", "ua"))
            .isInstanceOf(RateLimitException.class);

        verify(emailChangeRepo, never()).cancelPendingForUser(any(), any());
    }

    @Test
    void requestEmailChange_throws401_whenUserNotFound() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.empty());

        final ChangeEmailRequest r = req();
        assertThatThrownBy(() -> service.requestEmailChange(USER_ID, r, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void requestEmailChange_throws401_whenAccountHasNoPassword() {
        when(user.getPasswordHash()).thenReturn(null);

        final ChangeEmailRequest r = req();
        assertThatThrownBy(() -> service.requestEmailChange(USER_ID, r, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void requestEmailChange_throws401_whenCurrentPasswordWrong() {
        when(passwordEncoder.matches(CURRENT_PASSWORD, "hashed-current-password")).thenReturn(false);

        final ChangeEmailRequest r = req();
        assertThatThrownBy(() -> service.requestEmailChange(USER_ID, r, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(emailChangeRepo, never()).save(any());
        verify(emailChangeRepo, never()).cancelPendingForUser(any(), any());
    }

    @Test
    void requestEmailChange_cancelsAnyPreviousPendingRequest() {
        service.requestEmailChange(USER_ID, req(), "ip", "ua");

        verify(emailChangeRepo).cancelPendingForUser(eq(USER_ID), any(Instant.class));
    }

    @Test
    void requestEmailChange_duplicateTarget_sendsNoticeToOwner_savesNoToken_stillCompletes() {
        final User owner = org.mockito.Mockito.mock(User.class);
        when(owner.getFirstName()).thenReturn("Bob");
        when(owner.getLocale()).thenReturn("en");
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(TENANT_ID, NEW_EMAIL))
            .thenReturn(Optional.of(owner));

        service.requestEmailChange(USER_ID, req(), "ip", "ua");

        verify(emailChangeRepo, never()).save(any());
        verify(emailService, never()).sendEmailChangeConfirmationEmail(any(), any(), any(), any());
        verify(emailService).sendEmailChangeDuplicateEmail(NEW_EMAIL, "Bob", Locale.ENGLISH);
        verify(auditService).log(user, AuditService.EMAIL_CHANGE_DUPLICATE_ATTEMPT, "ip", "ua");
        // A duplicate still cancels whatever was pending before — "only one active request".
        verify(emailChangeRepo).cancelPendingForUser(eq(USER_ID), any(Instant.class));
    }

    @Test
    void requestEmailChange_happyPath_savesHashedToken_sendsConfirmationEmail_logsAudit() {
        when(user.getLocale()).thenReturn("fr");

        service.requestEmailChange(USER_ID, req(), "ip", "ua");

        final ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmailChangeConfirmationEmail(
            eq(NEW_EMAIL), eq("Alice"), tokenCaptor.capture(), eq(Locale.FRENCH));
        final String rawToken = tokenCaptor.getValue();
        assertThat(rawToken).isNotBlank();

        final ArgumentCaptor<EmailChangeRequest> savedCaptor = ArgumentCaptor.forClass(EmailChangeRequest.class);
        verify(emailChangeRepo).save(savedCaptor.capture());
        final EmailChangeRequest saved = savedCaptor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getNewEmail()).isEqualTo(NEW_EMAIL);
        // The raw token is never persisted — only its SHA-256 hash is.
        assertThat(saved.getTokenHash()).isEqualTo(CryptoUtils.sha256(rawToken));
        assertThat(saved.getExpiresAt()).isAfter(Instant.now().plusSeconds(3600 * 23));

        verify(emailService, never()).sendEmailChangeDuplicateEmail(any(), any(), any());
        verify(auditService).log(user, AuditService.EMAIL_CHANGE_REQUESTED, "ip", "ua");
    }

    @Test
    void requestEmailChange_normalizesEmailCase() {
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(TENANT_ID, "mixed@x.com"))
            .thenReturn(Optional.empty());
        when(user.getLocale()).thenReturn("fr");

        service.requestEmailChange(USER_ID, new ChangeEmailRequest("Mixed@X.com", CURRENT_PASSWORD), "ip", "ua");

        final ArgumentCaptor<EmailChangeRequest> captor = ArgumentCaptor.forClass(EmailChangeRequest.class);
        verify(emailChangeRepo).save(captor.capture());
        assertThat(captor.getValue().getNewEmail()).isEqualTo("mixed@x.com");
    }

    // ---------------- confirmEmailChange ----------------

    private EmailChangeRequest pendingRequest(final String rawToken, final Instant expiresAt) {
        final EmailChangeRequest ecr = new EmailChangeRequest();
        ecr.setUser(user);
        ecr.setNewEmail(NEW_EMAIL);
        ecr.setTokenHash(CryptoUtils.sha256(rawToken));
        ecr.setExpiresAt(expiresAt);
        return ecr;
    }

    @Test
    void confirmEmailChange_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.confirmEmailChange("tok", "ip", "ua"))
            .isInstanceOf(RateLimitException.class);
        verify(emailChangeRepo, never()).findByTokenHash(any());
    }

    @Test
    void confirmEmailChange_throwsInvalid_whenTokenNotFound() {
        when(emailChangeRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmEmailChange("unknown", "ip", "ua"))
            .isInstanceOf(EmailChangeTokenException.class)
            .extracting(e -> ((EmailChangeTokenException) e).getReason())
            .isEqualTo(EmailChangeTokenException.Reason.INVALID);
    }

    @Test
    void confirmEmailChange_throwsAlreadyUsed_whenUsedAtAlreadySet() {
        final EmailChangeRequest ecr = pendingRequest("tok", Instant.now().plusSeconds(600));
        ecr.setUsedAt(Instant.now().minusSeconds(60));
        when(emailChangeRepo.findByTokenHash(CryptoUtils.sha256("tok"))).thenReturn(Optional.of(ecr));

        assertThatThrownBy(() -> service.confirmEmailChange("tok", "ip", "ua"))
            .isInstanceOf(EmailChangeTokenException.class)
            .extracting(e -> ((EmailChangeTokenException) e).getReason())
            .isEqualTo(EmailChangeTokenException.Reason.ALREADY_USED);
        verify(userRepo, never()).save(any());
    }

    @Test
    void confirmEmailChange_throwsAlreadyUsed_whenCancelledBySupersedingRequest() {
        final EmailChangeRequest ecr = pendingRequest("tok", Instant.now().plusSeconds(600));
        ecr.setCancelledAt(Instant.now().minusSeconds(60));
        when(emailChangeRepo.findByTokenHash(CryptoUtils.sha256("tok"))).thenReturn(Optional.of(ecr));

        assertThatThrownBy(() -> service.confirmEmailChange("tok", "ip", "ua"))
            .isInstanceOf(EmailChangeTokenException.class)
            .extracting(e -> ((EmailChangeTokenException) e).getReason())
            .isEqualTo(EmailChangeTokenException.Reason.ALREADY_USED);
    }

    @Test
    void confirmEmailChange_throwsExpired_whenPastTtl() {
        final EmailChangeRequest ecr = pendingRequest("tok", Instant.now().minusSeconds(60));
        when(emailChangeRepo.findByTokenHash(CryptoUtils.sha256("tok"))).thenReturn(Optional.of(ecr));

        assertThatThrownBy(() -> service.confirmEmailChange("tok", "ip", "ua"))
            .isInstanceOf(EmailChangeTokenException.class)
            .extracting(e -> ((EmailChangeTokenException) e).getReason())
            .isEqualTo(EmailChangeTokenException.Reason.EXPIRED);
        verify(emailChangeRepo, never()).markUsed(any(), any());
    }

    @Test
    void confirmEmailChange_throwsAlreadyUsed_whenMarkUsedLosesRace() {
        final EmailChangeRequest ecr = pendingRequest("tok", Instant.now().plusSeconds(600));
        when(emailChangeRepo.findByTokenHash(CryptoUtils.sha256("tok"))).thenReturn(Optional.of(ecr));
        when(emailChangeRepo.markUsed(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirmEmailChange("tok", "ip", "ua"))
            .isInstanceOf(EmailChangeTokenException.class)
            .extracting(e -> ((EmailChangeTokenException) e).getReason())
            .isEqualTo(EmailChangeTokenException.Reason.ALREADY_USED);
        verify(userRepo, never()).save(any());
    }

    @Test
    void confirmEmailChange_throwsTargetTaken_whenAddressClaimedMeanwhile_doesNotChangeEmail() {
        final EmailChangeRequest ecr = pendingRequest("tok", Instant.now().plusSeconds(600));
        when(emailChangeRepo.findByTokenHash(CryptoUtils.sha256("tok"))).thenReturn(Optional.of(ecr));
        when(emailChangeRepo.markUsed(any(), any())).thenReturn(1);
        when(userRepo.existsByTenantIdAndEmailAndDeletedAtIsNull(TENANT_ID, NEW_EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> service.confirmEmailChange("tok", "ip", "ua"))
            .isInstanceOf(EmailChangeTargetTakenException.class);

        verify(user, never()).setEmail(any());
        verify(userRepo, never()).save(any());
        verify(securityNotificationService, never()).notifyEmailChanged(any(), any(), any(), any(), any());
        verify(auditService).log(user, AuditService.EMAIL_CHANGE_TARGET_TAKEN, "ip", "ua");
    }

    @Test
    void confirmEmailChange_happyPath_updatesEmail_notifiesOldAddress_logsAudit() {
        when(user.getLocale()).thenReturn("fr");
        final EmailChangeRequest ecr = pendingRequest("tok", Instant.now().plusSeconds(600));
        when(emailChangeRepo.findByTokenHash(CryptoUtils.sha256("tok"))).thenReturn(Optional.of(ecr));
        when(emailChangeRepo.markUsed(any(), any())).thenReturn(1);

        service.confirmEmailChange("tok", "ip", "ua");

        verify(user).setEmail(NEW_EMAIL);
        verify(userRepo).saveAndFlush(user);
        verify(securityNotificationService).notifyEmailChanged(
            eq(user), eq("old@x.com"), eq(NEW_EMAIL), any(Instant.class), eq("ip"));
        verify(auditService).log(user, AuditService.EMAIL_CHANGE_CONFIRMED, "ip", "ua");
    }

    @Test
    void confirmEmailChange_throwsTargetTaken_whenConcurrentConfirmationWinsUniqueConstraintRace() {
        // Two confirmations for the same target address both pass the existsBy pre-check
        // (TOCTOU window) — only one wins the idx_users_tenant_email unique constraint on
        // flush. The loser must get the same clean 409 as the pre-check branch, not a raw 500.
        final EmailChangeRequest ecr = pendingRequest("tok", Instant.now().plusSeconds(600));
        when(emailChangeRepo.findByTokenHash(CryptoUtils.sha256("tok"))).thenReturn(Optional.of(ecr));
        when(emailChangeRepo.markUsed(any(), any())).thenReturn(1);
        when(userRepo.existsByTenantIdAndEmailAndDeletedAtIsNull(TENANT_ID, NEW_EMAIL)).thenReturn(false);
        when(userRepo.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException("idx_users_tenant_email"));

        assertThatThrownBy(() -> service.confirmEmailChange("tok", "ip", "ua"))
            .isInstanceOf(EmailChangeTargetTakenException.class);

        verify(securityNotificationService, never()).notifyEmailChanged(any(), any(), any(), any(), any());
        verify(auditService).log(user, AuditService.EMAIL_CHANGE_TARGET_TAKEN, "ip", "ua");
    }
}
