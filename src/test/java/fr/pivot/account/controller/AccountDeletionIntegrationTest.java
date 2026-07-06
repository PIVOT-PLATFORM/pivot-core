package fr.pivot.account.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.account.entity.AccountDeletionRequest;
import fr.pivot.account.repository.AccountDeletionRequestRepository;
import fr.pivot.account.service.AccountDeletionService;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.EmailService;
import fr.pivot.auth.service.TokenService;
import fr.pivot.tenant.entity.Tenant;
import fr.pivot.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the account-deletion flow (US02.2.4, RGPD Art. 17) — full Spring
 * context, real PostgreSQL (Testcontainers, inherited from {@link AbstractIntegrationTest}) and
 * real Redis (Testcontainers, {@link #REDIS}) so the OTP rate limiter genuinely exercises Redis.
 *
 * <p>Every test creates its own isolated user (unique email) rather than reusing the shared
 * V2 seed accounts — {@code deleted_at} mutation is not something other test classes sharing the
 * same Testcontainers Postgres instance should ever observe.
 *
 * <p>Traceability (US02.2.4 AC table):
 * <ul>
 *   <li>"DELETE confirme avec mot de passe actuel" + "403 si invalide" —
 *       {@code local_*}</li>
 *   <li>"OTP 6 chiffres ... sans confirmation valide -> 403" — {@code otp_*}</li>
 *   <li>"tous tokens révoqués immédiatement, PENDING_DELETION, invisible admin" —
 *       {@code local_happyPath_*}, {@code adminList_*}</li>
 *   <li>"401 à toute connexion" pendant le délai de grâce — {@code login_*}</li>
 *   <li>"annulation via lien" — {@code cancel_*}</li>
 *   <li>"anonymisation après délai de grâce" (RGPD Art. 17) — {@code anonymize_*}</li>
 * </ul>
 */
@Import(AccountDeletionIntegrationTest.TestMailConfig.class)
class AccountDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/account";
    private static final String TEST_PASSWORD = "Pivot@Test123!";
    /** BCrypt-12 hash of {@link #TEST_PASSWORD} — same seeded value as V2__test_seeds.sql. */
    private static final String TEST_PASSWORD_HASH =
        "$2b$10$OIQ8qu5fOvZmxqiXVBpIN.5GPuOTs1io4..ChdCfpG8/OcbrPL1We";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureRedis(final DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private UserRepository userRepo;
    @Autowired private TenantRepository tenantRepo;
    @Autowired private AccessTokenRepository tokenRepo;
    @Autowired private TokenService tokenService;
    @Autowired private EmailService emailService;
    @Autowired private AccountDeletionService accountDeletionService;
    @Autowired private AccountDeletionRequestRepository deletionRequestRepo;

    private MockMvc mockMvc;

    private MockMvc mockMvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        }
        return mockMvc;
    }

    /**
     * No user-row cleanup here: every test creates a user with a fresh random-UUID email, so
     * there is no cross-test collision risk, and {@code audit_events} rows written against these
     * users (accountability RGPD Art. 5.2 — {@code ON DELETE RESTRICT}, see V1 schema) would
     * make a hard {@code DELETE} of the user row fail anyway. The Testcontainers Postgres
     * instance is destroyed with the JVM at the end of the whole test run.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        reset(emailService);
    }

    private User createLocalUser(final String emailPrefix) {
        final Tenant tenant = tenantRepo.findBySlug("pivot-saas").orElseThrow();
        final User user = new User();
        user.setTenant(tenant);
        user.setEmail(emailPrefix + "-" + UUID.randomUUID() + "@pivot.test");
        user.setPasswordHash(TEST_PASSWORD_HASH);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setEmailVerified(true);
        return userRepo.save(user);
    }

    private User createOidcUser(final String emailPrefix) {
        final Tenant tenant = tenantRepo.findBySlug("pivot-saas").orElseThrow();
        final User user = new User();
        user.setTenant(tenant);
        user.setEmail(emailPrefix + "-" + UUID.randomUUID() + "@pivot.test");
        user.setOidcSubject("oidc-subject-" + UUID.randomUUID());
        user.setFirstName("Oidc");
        user.setLastName("User");
        user.setEmailVerified(true);
        return userRepo.save(user);
    }

    private String issueToken(final User user) {
        return tokenService.issue(user, null, null, "JUnit", "127.0.0.1", AuthMethod.PASSWORD, false).rawToken();
    }

    // ---------------- LOCAL (password) confirmation ----------------

    @Test
    void local_wrongPassword_returns403_accountUntouched() throws Exception {
        final User user = createLocalUser("wrong-pw");
        final String token = issueToken(user);

        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"WrongPassword1!"}
                    """))
            .andExpect(status().isForbidden());

        assertThat(userRepo.findById(user.getId()).orElseThrow().getDeletedAt()).isNull();
        assertThat(tokenService.validate(token)).isPresent();
    }

    @Test
    void local_happyPath_returns200_revokesTokens_setsPendingDeletion_sendsConfirmationEmail() throws Exception {
        final User user = createLocalUser("happy-pw");
        final String token = issueToken(user);
        final String otherToken = issueToken(user);

        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s"}
                    """.formatted(TEST_PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.effectiveDeletionDate").exists());

        final User reloaded = userRepo.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getScheduledDeletionAt()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
        assertThat(reloaded.getAnonymizedAt()).isNull();

        // Every session revoked — including the one that authenticated this request.
        assertThat(tokenService.validate(token)).isEmpty();
        assertThat(tokenService.validate(otherToken)).isEmpty();

        verify(emailService).sendAccountDeletionConfirmationEmail(
            eq(reloaded.getEmail()), anyString(), any(Instant.class), anyString(), any());
    }

    @Test
    void local_alreadyPending_returns409() throws Exception {
        final User user = createLocalUser("already-pending");
        final String token1 = issueToken(user);
        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\"}".formatted(TEST_PASSWORD)))
            .andExpect(status().isOk());

        final String token2 = issueToken(user);
        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token2)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\"}".formatted(TEST_PASSWORD)))
            .andExpect(status().isConflict());
    }

    // ---------------- Login blocked during grace period ----------------

    @Test
    void login_returns401_duringGracePeriod() throws Exception {
        final User user = createLocalUser("login-blocked");
        final String token = issueToken(user);
        final String email = user.getEmail();

        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\"}".formatted(TEST_PASSWORD)))
            .andExpect(status().isOk());

        mockMvc().perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, TEST_PASSWORD)))
            .andExpect(status().isUnauthorized());
    }

    // ---------------- Cancellation ----------------

    @Test
    void cancel_restoresAccount_allowsLoginAgain() throws Exception {
        final User user = createLocalUser("cancel-flow");
        final String token = issueToken(user);
        final String email = user.getEmail();

        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\"}".formatted(TEST_PASSWORD)))
            .andExpect(status().isOk());

        final org.mockito.ArgumentCaptor<String> cancelTokenCaptor = forClass(String.class);
        verify(emailService).sendAccountDeletionConfirmationEmail(
            anyString(), anyString(), any(Instant.class), cancelTokenCaptor.capture(), any());
        final String rawCancelToken = cancelTokenCaptor.getValue();

        mockMvc().perform(post("/account/deletion/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"%s"}
                    """.formatted(rawCancelToken)))
            .andExpect(status().isOk());

        final User reloaded = userRepo.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNull();
        assertThat(reloaded.getScheduledDeletionAt()).isNull();

        final Optional<AccountDeletionRequest> request =
            deletionRequestRepo.findFirstByUserIdOrderByRequestedAtDesc(user.getId());
        assertThat(request).isPresent();
        assertThat(request.get().getCancelledAt()).isNotNull();

        // Login works again (a fresh session is required — the old one was revoked at request time).
        mockMvc().perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(email, TEST_PASSWORD)))
            .andExpect(status().isOk());
    }

    @Test
    void cancel_unknownToken_returns400() throws Exception {
        mockMvc().perform(post("/account/deletion/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"unknown-token"}
                    """))
            .andExpect(status().isBadRequest());
    }

    // ---------------- OTP (OIDC / no local password) confirmation ----------------

    @Test
    void otp_requestThenConfirm_happyPath() throws Exception {
        final User user = createOidcUser("otp-flow");
        final String token = issueOidcToken(user);

        mockMvc().perform(post("/account/deletion/otp")
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token))
            .andExpect(status().isAccepted());

        final org.mockito.ArgumentCaptor<String> otpCaptor = forClass(String.class);
        verify(emailService).sendAccountDeletionOtpEmail(anyString(), anyString(), otpCaptor.capture(), any());
        final String otp = otpCaptor.getValue();
        assertThat(otp).matches("\\d{6}");

        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"otp":"%s"}
                    """.formatted(otp)))
            .andExpect(status().isOk());

        assertThat(userRepo.findById(user.getId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    @Test
    void otp_deleteWithoutRequestingFirst_returns403() throws Exception {
        final User user = createOidcUser("otp-no-request");
        final String token = issueOidcToken(user);

        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"otp":"123456"}
                    """))
            .andExpect(status().isForbidden());
    }

    @Test
    void otp_deleteWithNoConfirmationAtAll_returns403() throws Exception {
        final User user = createOidcUser("otp-no-confirmation");
        final String token = issueOidcToken(user);

        // Explicit empty JSON body (rather than no body at all) to avoid depending on how
        // Spring resolves a fully bodyless @RequestBody(required=false) with no Content-Type.
        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    private String issueOidcToken(final User user) {
        return tokenService.issue(user, null, null, "JUnit", "127.0.0.1", AuthMethod.OIDC, false).rawToken();
    }

    // ---------------- Scheduled anonymization (RGPD Art. 17 purge) ----------------

    @Test
    void anonymize_afterGracePeriodElapses_scrubsPii_keepsAuditTrail() throws Exception {
        final User user = createLocalUser("anonymize-flow");
        final String token = issueToken(user);
        final String originalEmail = user.getEmail();

        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\"}".formatted(TEST_PASSWORD)))
            .andExpect(status().isOk());

        // Simulate the grace period having elapsed — back-date scheduled_deletion_at directly,
        // then invoke the scheduled job's business method (AccountDeletionScheduler just
        // delegates to it) rather than waiting 30 real days or reconfiguring @Scheduled.
        final User pending = userRepo.findById(user.getId()).orElseThrow();
        pending.setScheduledDeletionAt(Instant.now().minusSeconds(60));
        userRepo.save(pending);

        accountDeletionService.anonymizeDueAccounts();

        final User anonymized = userRepo.findById(user.getId()).orElseThrow();
        assertThat(anonymized.getEmail()).matches("deleted-[0-9a-f-]{36}@pivot\\.invalid");
        assertThat(anonymized.getEmail()).isNotEqualTo(originalEmail);
        assertThat(anonymized.getFirstName()).isNull();
        assertThat(anonymized.getLastName()).isNull();
        assertThat(anonymized.getAvatarUrl()).isNull();
        assertThat(anonymized.getPasswordHash()).isNull();
        assertThat(anonymized.getAnonymizedAt()).isNotNull();
        // Still soft-deleted / not resurrected.
        assertThat(anonymized.getDeletedAt()).isNotNull();
    }

    @Test
    void anonymize_isNoOp_beforeGracePeriodElapses() throws Exception {
        final User user = createLocalUser("not-yet-due");
        final String token = issueToken(user);

        mockMvc().perform(delete(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\"}".formatted(TEST_PASSWORD)))
            .andExpect(status().isOk());

        accountDeletionService.anonymizeDueAccounts();

        final User stillPending = userRepo.findById(user.getId()).orElseThrow();
        assertThat(stillPending.getAnonymizedAt()).isNull();
        assertThat(stillPending.getFirstName()).isEqualTo("Test");
    }

    // ----------------------------------------------------------------
    // Test wiring
    // ----------------------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class TestMailConfig {

        @Bean
        @Primary
        EmailService emailService() {
            return mock(EmailService.class);
        }
    }
}
