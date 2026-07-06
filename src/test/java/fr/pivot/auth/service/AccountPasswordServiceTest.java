package fr.pivot.auth.service;

import fr.pivot.auth.dto.ChangePasswordRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.exception.ChangePasswordRateLimitException;
import fr.pivot.auth.exception.InvalidCurrentPasswordException;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.tenant.entity.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountPasswordService} — the authenticated change-password flow
 * (US02.2.1): current-password verification, dual rate limiting, full session revocation +
 * replacement token issuance, and confirmation email.
 *
 * <p>Traceability (US02.2.1 AC table):
 * <ul>
 *   <li>"exige mot de passe actuel" — {@code ac0221_currentPassword_*}</li>
 *   <li>"tous les tokens de session existants révoqués" + "révocation inclut le token courant ;
 *       nouveau token émis et retourné" + "session courante préservée" —
 *       {@code ac0221_happyPath_*}</li>
 *   <li>"rate limiting par userId ET par IP ... message indistinguable" —
 *       {@code ac0221_rateLimit_*}</li>
 *   <li>"email de confirmation envoyé" — {@code ac0221_happyPath_sendsConfirmationEmail}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountPasswordServiceTest {

    private static final Long USER_ID = 7L;
    private static final String IP = "203.0.113.9";
    private static final String USER_AGENT = "JUnit";
    private static final String USER_BUCKET = "change-password:user:7";
    private static final String IP_BUCKET = "change-password:ip:" + IP;
    private static final String OLD_HASH = "old-hash";
    private static final String NEW_HASH = "new-hash";
    private static final String OLD_RAW_PASSWORD = "OldPassword1!";
    private static final String NEW_RAW_PASSWORD = "NewPassword1!";
    private static final String RAW_NEW_TOKEN = "raw-new-token";
    private static final String WRONG_PASSWORD_MESSAGE = "Mot de passe actuel incorrect";

    @Mock private UserRepository userRepo;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenService tokenService;
    @Mock private EmailService emailService;
    @Mock private RateLimiterService rateLimiter;
    @Mock private AuditService auditService;
    @Mock private MessageSource messageSource;
    @Mock private User user;
    @Mock private Tenant tenant;

    private AccountPasswordService service;

    @BeforeEach
    void setUp() {
        service = new AccountPasswordService(
            userRepo, passwordEncoder, tokenService, emailService, rateLimiter, auditService, messageSource);

        when(messageSource.getMessage(eq("account.change-password.auth-failure"), any(), eq(Locale.FRENCH)))
            .thenReturn(WRONG_PASSWORD_MESSAGE);

        when(rateLimiter.changePasswordUserBucket(USER_ID.toString())).thenReturn(USER_BUCKET);
        when(rateLimiter.changePasswordIpBucket(IP)).thenReturn(IP_BUCKET);
        when(rateLimiter.isAllowed(any(), eq(5), any(Duration.class))).thenReturn(true);

        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(USER_ID);
        when(user.getEmail()).thenReturn("user@x.com");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getLocale()).thenReturn("fr");
        when(user.getPasswordHash()).thenReturn(OLD_HASH);
        when(user.getTenant()).thenReturn(tenant);
        when(tenant.getId()).thenReturn(1L);
        when(tenant.getSlug()).thenReturn("pivot-saas");
    }

    private ChangePasswordRequest request() {
        return new ChangePasswordRequest(OLD_RAW_PASSWORD, NEW_RAW_PASSWORD);
    }

    /** Stubs a successful current-password check, hash re-encoding and token issuance. */
    private void stubHappyPath() {
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(passwordEncoder.encode(any())).thenReturn(NEW_HASH);
        when(tokenService.issue(any(), any(), any(), any(), any(), any(), eq(false)))
            .thenReturn(new TokenService.TokenIssueResult(RAW_NEW_TOKEN, Instant.now().plusSeconds(3600), 3600));
    }

    // ---------------- Rate limiting ----------------

    @Test
    void ac0221_rateLimit_throwsWithSameMessageAsWrongPassword_whenUserBucketLocked() {
        when(rateLimiter.isAllowed(eq(USER_BUCKET), eq(5), any())).thenReturn(false);
        when(rateLimiter.getRemainingSeconds(USER_BUCKET)).thenReturn(300L);
        when(rateLimiter.getRemainingSeconds(IP_BUCKET)).thenReturn(0L);

        final ChangePasswordRequest req = request();
        assertThatThrownBy(() -> service.changePassword(USER_ID, req, IP, USER_AGENT))
            .isInstanceOf(ChangePasswordRateLimitException.class)
            .hasMessage(WRONG_PASSWORD_MESSAGE)
            .extracting(e -> ((ChangePasswordRateLimitException) e).getRetryAfterSeconds())
            .isEqualTo(300L);

        verify(userRepo, never()).findById(any());
    }

    @Test
    void ac0221_rateLimit_throwsWithSameMessageAsWrongPassword_whenIpBucketLocked() {
        when(rateLimiter.isAllowed(eq(IP_BUCKET), eq(5), any())).thenReturn(false);
        when(rateLimiter.getRemainingSeconds(USER_BUCKET)).thenReturn(0L);
        when(rateLimiter.getRemainingSeconds(IP_BUCKET)).thenReturn(120L);

        final ChangePasswordRequest req = request();
        assertThatThrownBy(() -> service.changePassword(USER_ID, req, IP, USER_AGENT))
            .isInstanceOf(ChangePasswordRateLimitException.class)
            .hasMessage(WRONG_PASSWORD_MESSAGE);
    }

    // ---------------- Wrong current password ----------------

    @Test
    void ac0221_currentPassword_throws401_whenCurrentPasswordWrong() {
        when(passwordEncoder.matches(OLD_RAW_PASSWORD, OLD_HASH)).thenReturn(false);

        final ChangePasswordRequest req = request();
        assertThatThrownBy(() -> service.changePassword(USER_ID, req, IP, USER_AGENT))
            .isInstanceOf(InvalidCurrentPasswordException.class)
            .hasMessage(WRONG_PASSWORD_MESSAGE);

        verify(rateLimiter).recordAttempt(USER_BUCKET, Duration.ofMinutes(15));
        verify(rateLimiter).recordAttempt(IP_BUCKET, Duration.ofMinutes(15));
        verify(auditService).log(user, AuditService.CHANGE_PASSWORD_FAILED, IP, USER_AGENT);
        verify(userRepo, never()).save(any());
        verify(tokenService, never()).revokeAllForUser(any());
    }

    // ---------------- Happy path ----------------

    @Test
    void ac0221_happyPath_updatesPasswordHash() {
        stubHappyPath();

        service.changePassword(USER_ID, request(), IP, USER_AGENT);

        verify(user).setPasswordHash(NEW_HASH);
        verify(userRepo).save(user);
    }

    @Test
    void ac0221_happyPath_revokesAllSessions_includingCurrentToken() {
        stubHappyPath();

        service.changePassword(USER_ID, request(), IP, USER_AGENT);

        // Revocation targets ALL active sessions for the user — the current token is not
        // excluded — see AccountPasswordService class-level javadoc for the rationale.
        verify(tokenService).revokeAllForUser(USER_ID);
    }

    @Test
    void ac0221_happyPath_issuesFreshTokenAndReturnsItInResult() {
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(passwordEncoder.encode(any())).thenReturn(NEW_HASH);
        final Instant expiresAt = Instant.now().plusSeconds(86_400);
        when(tokenService.issue(user, null, null, USER_AGENT, IP, AuthMethod.PASSWORD, false))
            .thenReturn(new TokenService.TokenIssueResult(RAW_NEW_TOKEN, expiresAt, 86_400));

        final LoginResult result = service.changePassword(USER_ID, request(), IP, USER_AGENT);

        assertThat(result.requiresDeviceVerification()).isFalse();
        assertThat(result.sessionToken()).isEqualTo(RAW_NEW_TOKEN);
        assertThat(result.expiresAt()).isEqualTo(expiresAt.toEpochMilli());
        assertThat(result.sessionTtlSeconds()).isEqualTo(86_400);
        assertThat(result.user().id()).isEqualTo(USER_ID);
        assertThat(result.user().tenantSlug()).isEqualTo("pivot-saas");
    }

    @Test
    void ac0221_happyPath_sendsConfirmationEmail() {
        stubHappyPath();

        service.changePassword(USER_ID, request(), IP, USER_AGENT);

        verify(emailService).sendPasswordChangedEmail(
            eq("user@x.com"), eq("Alice"), any(Instant.class), eq(IP), eq(Locale.FRENCH));
        verify(auditService).log(user, AuditService.CHANGE_PASSWORD, IP, USER_AGENT);
    }

    @Test
    void ac0221_happyPath_resetsRateLimitBuckets() {
        stubHappyPath();

        service.changePassword(USER_ID, request(), IP, USER_AGENT);

        verify(rateLimiter).reset(USER_BUCKET);
        verify(rateLimiter).reset(IP_BUCKET);
    }

    // ---------------- Defensive: user not found ----------------

    @Test
    void ac0221_throws401_whenAuthenticatedUserNoLongerExists() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.empty());

        final ChangePasswordRequest req = request();
        assertThatThrownBy(() -> service.changePassword(USER_ID, req, IP, USER_AGENT))
            .isInstanceOf(InvalidCurrentPasswordException.class);
    }
}
