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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountDeletionService} — RGPD Art. 17 account-deletion flow
 * (US02.2.4): dual-path confirmation (password / OTP), immediate effects (session revocation,
 * PENDING_DELETION, confirmation email), cancellation and scheduled anonymization.
 *
 * <p>Traceability (US02.2.4 AC table):
 * <ul>
 *   <li>"DELETE /api/account confirme avec mot de passe actuel" —
 *       {@code requestDeletion_local_*}</li>
 *   <li>"OIDC : OTP 6 chiffres ... sans confirmation valide -> 403" —
 *       {@code requestDeletion_otp_*}</li>
 *   <li>"tous tokens révoqués immédiatement, PENDING_DELETION" —
 *       {@code requestDeletion_happyPath_*}</li>
 *   <li>"Email de confirmation avec date effective" — {@code requestDeletion_happyPath_*}</li>
 *   <li>"Audit event AccountDeleted" — {@code requestDeletion_happyPath_*}</li>
 *   <li>"annulation via lien" — {@code cancelDeletion_*}</li>
 *   <li>"anonymisation après délai de grâce" — {@code anonymize_*}, {@code anonymizeDueAccounts_*}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountDeletionServiceTest {

    private static final Long USER_ID = 42L;
    private static final String OTP_SECRET = "test-otp-secret";

    @Mock private UserRepository userRepo;
    @Mock private AccountDeletionRequestRepository deletionRequestRepo;
    @Mock private AccountDeletionOtpRepository deletionOtpRepo;
    @Mock private FeatureFlagRepository featureFlagRepo;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenService tokenService;
    @Mock private AvatarStorageService avatarStorageService;
    @Mock private EmailService emailService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private AuditService auditService;
    @Mock private AccountDeletionService self;

    @Mock private User user;

    private AccountDeletionService service;

    @BeforeEach
    void setUp() {
        service = new AccountDeletionService(
            userRepo, deletionRequestRepo, deletionOtpRepo, featureFlagRepo, passwordEncoder,
            tokenService, avatarStorageService, emailService, rateLimiter, auditService, OTP_SECRET, self);

        when(user.getId()).thenReturn(USER_ID);
        when(user.getEmail()).thenReturn("alice@x.com");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getLocale()).thenReturn("fr");
        when(rateLimiter.accountDeletionOtpBucket(anyString())).thenReturn("account-deletion-otp:user:42");
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(true);
        when(featureFlagRepo.getInt(eq("ACCOUNT_DELETION_GRACE_DAYS"), anyInt())).thenReturn(30);
        when(featureFlagRepo.getInt(eq("ACCOUNT_DELETION_OTP_TTL_MINUTES"), anyInt())).thenReturn(10);
    }

    // ---------------- confirmationMethod ----------------

    @Test
    void confirmationMethod_returnsPassword_whenLocalPasswordSet() {
        when(user.getPasswordHash()).thenReturn("hashed");
        assertThat(service.confirmationMethod(user)).isEqualTo(DeletionConfirmationMethod.PASSWORD);
    }

    @Test
    void confirmationMethod_returnsOtp_whenNoLocalPassword() {
        when(user.getPasswordHash()).thenReturn(null);
        assertThat(service.confirmationMethod(user)).isEqualTo(DeletionConfirmationMethod.OTP);
    }

    // ---------------- requestOtp ----------------

    @Test
    void requestOtp_throws400_whenAccountHasLocalPassword() {
        when(user.getPasswordHash()).thenReturn("hashed");

        assertThatThrownBy(() -> service.requestOtp(user, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(deletionOtpRepo, never()).save(any());
    }

    @Test
    void requestOtp_throws409_whenDeletionAlreadyPending() {
        when(user.getPasswordHash()).thenReturn(null);
        when(user.getDeletedAt()).thenReturn(Instant.now());

        assertThatThrownBy(() -> service.requestOtp(user, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void requestOtp_throws429_whenRateLimited() {
        when(user.getPasswordHash()).thenReturn(null);
        when(rateLimiter.checkAndRecord(anyString(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.requestOtp(user, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void requestOtp_happyPath_savesHashedOtp_sendsEmail_logsAudit() {
        when(user.getPasswordHash()).thenReturn(null);

        service.requestOtp(user, "ip", "ua");

        final ArgumentCaptor<AccountDeletionOtp> captor = ArgumentCaptor.forClass(AccountDeletionOtp.class);
        verify(deletionOtpRepo).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getOtpHash()).isNotBlank();
        assertThat(captor.getValue().getExpiresAt()).isAfter(Instant.now().plus(9, ChronoUnit.MINUTES));

        verify(emailService).sendAccountDeletionOtpEmail(eq("alice@x.com"), eq("Alice"), anyString(), any());
        verify(auditService).log(user, AuditService.ACCOUNT_DELETION_OTP_SENT, "ip", "ua");
    }

    // ---------------- requestDeletion — LOCAL (password) ----------------

    @Test
    void requestDeletion_throws409_whenDeletionAlreadyPending() {
        when(user.getDeletedAt()).thenReturn(Instant.now());

        assertThatThrownBy(() -> service.requestDeletion(user, new AccountDeletionRequestDto("x", null), "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        verify(tokenService, never()).revokeAllForUser(any());
    }

    @Test
    void requestDeletion_local_throws403_whenPasswordMissing() {
        when(user.getPasswordHash()).thenReturn("hashed");

        assertThatThrownBy(() -> service.requestDeletion(user, new AccountDeletionRequestDto(null, null), "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(tokenService, never()).revokeAllForUser(any());
        verify(userRepo, never()).save(any());
    }

    @Test
    void requestDeletion_local_throws403_whenPasswordWrong() {
        when(user.getPasswordHash()).thenReturn("hashed");
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.requestDeletion(
                user, new AccountDeletionRequestDto("wrong", null), "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(tokenService, never()).revokeAllForUser(any());
    }

    @Test
    void requestDeletion_local_happyPath_revokesTokens_setsPendingDeletion_sendsEmail_audits() {
        when(user.getPasswordHash()).thenReturn("hashed");
        when(passwordEncoder.matches("correct", "hashed")).thenReturn(true);

        final Instant effectiveAt = service.requestDeletion(
            user, new AccountDeletionRequestDto("correct", null), "ip", "ua");

        verify(tokenService).revokeAllForUser(USER_ID);
        verify(user).setDeletedAt(any(Instant.class));
        verify(user).setScheduledDeletionAt(effectiveAt);
        verify(userRepo).save(user);

        final ArgumentCaptor<AccountDeletionRequest> captor = ArgumentCaptor.forClass(AccountDeletionRequest.class);
        verify(deletionRequestRepo).save(captor.capture());
        final AccountDeletionRequest saved = captor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getConfirmedVia()).isEqualTo(DeletionConfirmationMethod.PASSWORD);
        assertThat(saved.getEffectiveAt()).isEqualTo(effectiveAt);
        assertThat(saved.getCancelTokenHash()).isNotBlank();

        assertThat(effectiveAt).isCloseTo(Instant.now().plus(30, ChronoUnit.DAYS), within(java.time.Duration.ofSeconds(5)));

        verify(emailService).sendAccountDeletionConfirmationEmail(
            eq("alice@x.com"), eq("Alice"), eq(effectiveAt), anyString(), any());
        verify(auditService).log(user, AuditService.ACCOUNT_DELETED, "ip", "ua");
    }

    private static org.assertj.core.data.TemporalUnitWithinOffset within(final java.time.Duration duration) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(duration.toMillis(), java.time.temporal.ChronoUnit.MILLIS);
    }

    // ---------------- requestDeletion — OTP (OIDC / no local password) ----------------

    @Test
    void requestDeletion_otp_throws403_whenOtpMissing() {
        when(user.getPasswordHash()).thenReturn(null);

        assertThatThrownBy(() -> service.requestDeletion(user, new AccountDeletionRequestDto(null, null), "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requestDeletion_otp_throws403_whenNoPendingOtp() {
        when(user.getPasswordHash()).thenReturn(null);
        when(deletionOtpRepo.findFirstByUserIdAndConfirmedAtIsNullOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestDeletion(
                user, new AccountDeletionRequestDto(null, "123456"), "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requestDeletion_otp_throws403_whenOtpExpired() {
        when(user.getPasswordHash()).thenReturn(null);
        final AccountDeletionOtp expired = new AccountDeletionOtp();
        expired.setUser(user);
        expired.setOtpHash(CryptoUtils.hmacSha256("123456", OTP_SECRET));
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        when(deletionOtpRepo.findFirstByUserIdAndConfirmedAtIsNullOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.requestDeletion(
                user, new AccountDeletionRequestDto(null, "123456"), "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requestDeletion_otp_throws403_andIncrementsAttempts_whenOtpWrong() {
        when(user.getPasswordHash()).thenReturn(null);
        final AccountDeletionOtp pending = new AccountDeletionOtp();
        pending.setUser(user);
        pending.setOtpHash(CryptoUtils.hmacSha256("123456", OTP_SECRET));
        pending.setExpiresAt(Instant.now().plusSeconds(600));
        when(deletionOtpRepo.findFirstByUserIdAndConfirmedAtIsNullOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.requestDeletion(
                user, new AccountDeletionRequestDto(null, "000000"), "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(pending.getAttempts()).isEqualTo(1);
        verify(deletionOtpRepo).save(pending);
        verify(tokenService, never()).revokeAllForUser(any());
    }

    @Test
    void requestDeletion_otp_throws403_whenAttemptsExhausted() {
        when(user.getPasswordHash()).thenReturn(null);
        final AccountDeletionOtp locked = new AccountDeletionOtp();
        locked.setUser(user);
        locked.setOtpHash(CryptoUtils.hmacSha256("123456", OTP_SECRET));
        locked.setExpiresAt(Instant.now().plusSeconds(600));
        locked.setAttempts(5);
        when(deletionOtpRepo.findFirstByUserIdAndConfirmedAtIsNullOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> service.requestDeletion(
                user, new AccountDeletionRequestDto(null, "123456"), "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requestDeletion_otp_happyPath_confirmsOtp_revokesTokens_setsPendingDeletion() {
        when(user.getPasswordHash()).thenReturn(null);
        final AccountDeletionOtp pending = new AccountDeletionOtp();
        pending.setUser(user);
        pending.setOtpHash(CryptoUtils.hmacSha256("123456", OTP_SECRET));
        pending.setExpiresAt(Instant.now().plusSeconds(600));
        when(deletionOtpRepo.findFirstByUserIdAndConfirmedAtIsNullOrderByCreatedAtDesc(USER_ID))
            .thenReturn(Optional.of(pending));

        final Instant effectiveAt = service.requestDeletion(
            user, new AccountDeletionRequestDto(null, "123456"), "ip", "ua");

        assertThat(pending.getConfirmedAt()).isNotNull();
        verify(tokenService).revokeAllForUser(USER_ID);

        final ArgumentCaptor<AccountDeletionRequest> captor = ArgumentCaptor.forClass(AccountDeletionRequest.class);
        verify(deletionRequestRepo).save(captor.capture());
        assertThat(captor.getValue().getConfirmedVia()).isEqualTo(DeletionConfirmationMethod.OTP);
        assertThat(effectiveAt).isNotNull();
    }

    // ---------------- cancelDeletion ----------------

    @Test
    void cancelDeletion_throws400_whenTokenUnknown() {
        when(deletionRequestRepo.findByCancelTokenHashAndCancelledAtIsNull(anyString()))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelDeletion("unknown-token", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cancelDeletion_throws410_whenAlreadyAnonymized() {
        final AccountDeletionRequest request = new AccountDeletionRequest();
        request.setUser(user);
        when(user.getAnonymizedAt()).thenReturn(Instant.now());
        when(deletionRequestRepo.findByCancelTokenHashAndCancelledAtIsNull(anyString()))
            .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.cancelDeletion("tok", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.GONE);
        verify(userRepo, never()).save(any());
    }

    @Test
    void cancelDeletion_happyPath_clearsDeletionState_sendsEmail_audits() {
        final AccountDeletionRequest request = new AccountDeletionRequest();
        request.setUser(user);
        when(user.getAnonymizedAt()).thenReturn(null);
        when(deletionRequestRepo.findByCancelTokenHashAndCancelledAtIsNull(anyString()))
            .thenReturn(Optional.of(request));

        service.cancelDeletion("tok", "ip", "ua");

        assertThat(request.getCancelledAt()).isNotNull();
        verify(deletionRequestRepo).save(request);
        verify(user).setDeletedAt(null);
        verify(user).setScheduledDeletionAt(null);
        verify(userRepo).save(user);
        verify(emailService).sendAccountDeletionCancelledEmail(eq("alice@x.com"), eq("Alice"), any());
        verify(auditService).log(user, AuditService.ACCOUNT_DELETION_CANCELLED, "ip", "ua");
    }

    // ---------------- anonymize ----------------

    @Test
    void anonymize_isNoOp_whenUserNotFound() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.empty());

        service.anonymize(USER_ID);

        verify(userRepo, never()).save(any());
        verify(auditService, never()).log(any(User.class), anyString(), any(), any());
    }

    @Test
    void anonymize_isNoOp_whenAlreadyAnonymized() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(user.getAnonymizedAt()).thenReturn(Instant.now());

        service.anonymize(USER_ID);

        verify(userRepo, never()).save(any());
        verify(avatarStorageService, never()).deleteIfManaged(any());
    }

    @Test
    void anonymize_happyPath_deletesAvatar_nullsPii_setsAnonymizedAt_audits() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(user.getAnonymizedAt()).thenReturn(null);
        when(user.getAvatarUrl()).thenReturn("/api/avatars/1/uuid.jpg");

        service.anonymize(USER_ID);

        verify(avatarStorageService).deleteIfManaged("/api/avatars/1/uuid.jpg");
        final ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
        verify(user).setEmail(emailCaptor.capture());
        assertThat(emailCaptor.getValue()).matches("deleted-[0-9a-f-]{36}@pivot\\.invalid");
        verify(user).setFirstName(null);
        verify(user).setLastName(null);
        verify(user).setAvatarUrl(null);
        verify(user).setPasswordHash(null);
        verify(user).setGoogleId(null);
        verify(user).setOidcSubject(null);
        verify(user).setAnonymizedAt(any(Instant.class));
        verify(userRepo).save(user);
        verify(auditService).log(user, AuditService.ACCOUNT_ANONYMIZED, null, null);
    }

    // ---------------- anonymizeDueAccounts ----------------

    @Test
    void anonymizeDueAccounts_isNoOp_whenNoneDue() {
        when(userRepo.findByDeletedAtIsNotNullAndAnonymizedAtIsNullAndScheduledDeletionAtBefore(any()))
            .thenReturn(List.of());

        service.anonymizeDueAccounts();

        verify(self, never()).anonymize(any());
    }

    @Test
    void anonymizeDueAccounts_delegatesToSelf_forEachDueUser_andContinuesAfterOneFails() {
        final User user1 = org.mockito.Mockito.mock(User.class);
        final User user2 = org.mockito.Mockito.mock(User.class);
        when(user1.getId()).thenReturn(1L);
        when(user2.getId()).thenReturn(2L);
        when(userRepo.findByDeletedAtIsNotNullAndAnonymizedAtIsNullAndScheduledDeletionAtBefore(any()))
            .thenReturn(List.of(user1, user2));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(self).anonymize(1L);

        service.anonymizeDueAccounts();

        verify(self, times(1)).anonymize(1L);
        verify(self, times(1)).anonymize(2L);
    }
}
