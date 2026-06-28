package fr.pivot.auth.service;

import fr.pivot.auth.dto.RegisterRequest;
import fr.pivot.auth.entity.EmailVerification;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.RateLimitException;
import fr.pivot.auth.repository.EmailVerificationRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.util.CryptoUtils;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

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
 * Unit tests for {@link RegistrationService} — registration, email verification
 * and resend flows with rate limiting and no-enumeration guarantees.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistrationServiceTest {

    @Mock private UserRepository userRepo;
    @Mock private TenantRepository tenantRepo;
    @Mock private EmailVerificationRepository emailVerifRepo;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private AuditService auditService;
    @Mock private Tenant tenant;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(userRepo, tenantRepo, emailVerifRepo, passwordEncoder,
            emailService, rateLimiter, auditService, 24L);
        when(rateLimiter.registerIpBucket(anyString())).thenReturn("register:ip");
        when(rateLimiter.verifyEmailBucket(anyString())).thenReturn("verify:ip");
        when(tenantRepo.findBySlug("pivot-saas")).thenReturn(Optional.of(tenant));
        when(tenant.getId()).thenReturn(1L);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    private User realUser() {
        final User u = new User();
        u.setEmail("user@x.com");
        u.setFirstName("Alice");
        return u;
    }

    private RegisterRequest req() {
        return new RegisterRequest("User@X.com", "password1", "Alice", "Doe", null);
    }

    // ---------------- register ----------------

    @Test
    void register_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(false);

        final RegisterRequest r = req();
        assertThatThrownBy(() -> service.register(r, "ip", "ua"))
            .isInstanceOf(RateLimitException.class);
    }

    /**
     * Anti-enumeration: duplicate email returns 200 (no 409).
     * Unverified account gets a verification reminder; BCrypt decoy runs to equalize timing.
     */
    @Test
    void register_isNeutral_whenEmailAlreadyExists() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com"))
            .thenReturn(Optional.of(realUser()));

        service.register(req(), "ip", "ua");

        verify(emailService).sendVerificationReminderEmail(eq("user@x.com"), eq("Alice"), anyString(), any(Locale.class));
        verify(emailVerifRepo).save(any(EmailVerification.class));
        verify(passwordEncoder).encode(anyString());
        verify(userRepo, never()).save(any(User.class));
        verify(emailService, never()).sendVerificationEmail(anyString(), anyString(), anyString(), any());
    }

    @Test
    void register_savesUserAndSendsVerification_onHappyPath() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(userRepo.existsByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(false);

        service.register(req(), "ip", "ua");

        verify(userRepo).save(any(User.class));
        verify(emailVerifRepo).save(any(EmailVerification.class));
        verify(emailService).sendVerificationEmail(eq("user@x.com"), eq("Alice"), anyString(), any(Locale.class));
        verify(auditService).log(any(User.class), eq(AuditService.REGISTER), eq("ip"), eq("ua"));
    }

    @Test
    void register_throws500_whenDefaultTenantMissing() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(tenantRepo.findBySlug("pivot-saas")).thenReturn(Optional.empty());

        final RegisterRequest r = req();
        assertThatThrownBy(() -> service.register(r, "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void register_usesEnLocale_forVerificationEmail() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        final ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        service.register(new RegisterRequest("en@x.com", "password1", "Bob", "Doe", "en"), "ip", "ua");

        verify(userRepo).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getLocale()).isEqualTo("en");
        verify(emailService).sendVerificationEmail(eq("en@x.com"), eq("Bob"), anyString(), eq(Locale.ENGLISH));
    }

    // ---------------- verifyEmail ----------------

    @Test
    void verifyEmail_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.verifyEmail("tok", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void verifyEmail_throws400_whenTokenInvalid() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(emailVerifRepo.findByTokenHashAndUsedAtIsNull(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyEmail("bad", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyEmail_throws400_whenTokenExpired() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        final EmailVerification ev = new EmailVerification();
        ev.setUser(realUser());
        ev.setExpiresAt(Instant.now().minusSeconds(10));
        when(emailVerifRepo.findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256("expired")))
            .thenReturn(Optional.of(ev));

        assertThatThrownBy(() -> service.verifyEmail("expired", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void verifyEmail_marksVerifiedAndSendsWelcome_onHappyPath() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        final User u = realUser();
        final EmailVerification ev = new EmailVerification();
        ev.setUser(u);
        ev.setExpiresAt(Instant.now().plusSeconds(600));
        when(emailVerifRepo.findByTokenHashAndUsedAtIsNull(CryptoUtils.sha256("valid")))
            .thenReturn(Optional.of(ev));

        service.verifyEmail("valid", "ip", "ua");

        assertThat(ev.getUsedAt()).isNotNull();
        assertThat(u.isEmailVerified()).isTrue();
        verify(emailService).sendWelcomeEmail(eq("user@x.com"), eq("Alice"), any(Locale.class));
        verify(auditService).log(u, AuditService.EMAIL_VERIFIED, "ip", "ua");
    }

    // ---------------- resendVerification ----------------

    @Test
    void resendVerification_throws429_whenRateLimited() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.resendVerification("a@b.c", "ip", "ua"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void resendVerification_isSilent_whenAlreadyVerified() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        final User u = realUser();
        u.setEmailVerified(true);
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.of(u));

        service.resendVerification("User@X.com", "ip", "ua");

        verify(emailVerifRepo, never()).save(any());
    }

    @Test
    void resendVerification_resends_whenNotVerified() {
        when(rateLimiter.checkAndRecord(any(), anyInt(), any())).thenReturn(true);
        when(userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@x.com")).thenReturn(Optional.of(realUser()));

        service.resendVerification("User@X.com", "ip", "ua");

        verify(emailVerifRepo).save(any(EmailVerification.class));
        verify(emailService).sendVerificationEmail(eq("user@x.com"), eq("Alice"), anyString(), any(Locale.class));
    }
}
