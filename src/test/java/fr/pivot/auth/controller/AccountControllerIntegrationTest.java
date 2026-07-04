package fr.pivot.auth.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.EmailService;
import fr.pivot.auth.service.TokenService;
import fr.pivot.auth.util.CryptoUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /account/password} (US02.2.1) — full Spring context, real
 * PostgreSQL (Testcontainers, inherited from {@link AbstractIntegrationTest}) and real Redis
 * (Testcontainers, {@link #REDIS}) so the sliding-window rate limiter genuinely exercises Redis.
 *
 * <p>The real {@link EmailService} is replaced by a Mockito mock ({@link TestMailConfig}) —
 * there is no SMTP server available in this test environment, mirroring the existing convention
 * in this codebase of never exercising a real email send from a TI test (see
 * {@code PasswordServiceTest}, which only verifies the email call via a TU mock).
 *
 * <p>Traceability (US02.2.1 AC table):
 * <ul>
 *   <li>"exige mot de passe actuel" + "aucun champ userId/accountId" + "validé selon politique
 *       de robustesse" — {@code ac0221_*_returns400/401}</li>
 *   <li>"tous les tokens révoqués" + "révocation inclut le token courant ; nouveau token émis et
 *       retourné" + "session courante préservée" — {@code ac0221_happyPath_*}</li>
 *   <li>"rate limiting par userId ET par IP ... 429 avec Retry-After ... indistinguable" —
 *       {@code ac0221_rateLimit_*}</li>
 * </ul>
 */
@Import(AccountControllerIntegrationTest.TestMailConfig.class)
class AccountControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String URL = "/account/password";
    private static final String TEST_USER_EMAIL = "user@pivot.test";
    /** Seeded in V2__test_seeds.sql for every test account, including {@link #TEST_USER_EMAIL}. */
    private static final String CURRENT_PASSWORD = "Pivot@Test123!";
    private static final String WRONG_PASSWORD_MESSAGE = "Mot de passe actuel incorrect";
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
    @Autowired private AccessTokenRepository tokenRepo;
    @Autowired private TokenService tokenService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private EmailService emailService;

    private MockMvc mockMvc;
    private User testUser;
    private String originalHash;
    private String rawToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();

        testUser = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, TEST_USER_EMAIL)
            .orElseThrow(() -> new IllegalStateException(
                "Test user 'user@pivot.test' not found — verify Flyway applied db/seeds (profile test)"));
        originalHash = testUser.getPasswordHash();
        rawToken = tokenService.issue(
            testUser, null, null, "JUnit", "127.0.0.1", AuthMethod.PASSWORD, false).rawToken();

        reset(emailService);
    }

    @AfterEach
    void tearDown() {
        // Restore the seeded password hash so other test methods keep authenticating with
        // CURRENT_PASSWORD regardless of execution order.
        final User user = userRepo.findById(testUser.getId()).orElseThrow();
        user.setPasswordHash(originalHash);
        userRepo.save(user);
        tokenRepo.deleteByUserId(testUser.getId());
        clearRateLimitKeys();
        SecurityContextHolder.clearContext();
    }

    private void clearRateLimitKeys() {
        final Set<String> keys = redis.keys("rl:change-password:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    // ---------------- Happy path ----------------

    @Test
    void ac0221_happyPath_returns200_revokesOldToken_andIssuesWorkingReplacement() throws Exception {
        // A second, unrelated active session must also be revoked — "tous les tokens de
        // session existants révoqués", not just the one used to authenticate this request.
        final String otherRawToken = tokenService.issue(
            testUser, "fp-other-device", "Firefox", "ua", "10.0.0.5", AuthMethod.PASSWORD, false).rawToken();

        final String responseBody = mockMvc.perform(post(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s","newPassword":"NewPassword1!"}
                    """.formatted(CURRENT_PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.user.email").value(TEST_USER_EMAIL))
            .andReturn().getResponse().getContentAsString();

        final String newToken = com.jayway.jsonpath.JsonPath.read(responseBody, "$.accessToken");
        assertThat(newToken).isNotEqualTo(rawToken);

        // The token that authenticated THIS request is revoked, not merely left alone.
        assertThat(tokenRepo.findByTokenHashAndStatus(CryptoUtils.sha256(rawToken), TokenStatus.ACTIVE))
            .isEmpty();
        // The unrelated second session is revoked too.
        assertThat(tokenRepo.findByTokenHashAndStatus(CryptoUtils.sha256(otherRawToken), TokenStatus.ACTIVE))
            .isEmpty();
        // The freshly issued replacement token is active and authenticates successfully —
        // "session courante préservée" via the new token.
        assertThat(tokenService.validate(newToken)).isPresent();

        verify(emailService).sendPasswordChangedEmail(
            eq(TEST_USER_EMAIL), anyString(), any(Instant.class), anyString(), any());
    }

    // ---------------- Wrong current password ----------------

    @Test
    void ac0221_wrongCurrentPassword_returns401_withGenericMessage() throws Exception {
        final String body = mockMvc.perform(post(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"WrongPassword1!","newPassword":"NewPassword1!"}
                    """))
            .andExpect(status().isUnauthorized())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(WRONG_PASSWORD_MESSAGE);
        // The original token is still valid — a wrong attempt must not revoke anything.
        assertThat(tokenService.validate(rawToken)).isPresent();
    }

    // ---------------- Mass-assignment / unexpected field ----------------

    @Test
    void ac0221_unexpectedUserIdField_returns400_beforeAnyPasswordCheck() throws Exception {
        mockMvc.perform(post(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s","newPassword":"NewPassword1!","userId":999}
                    """.formatted(CURRENT_PASSWORD)))
            .andExpect(status().isBadRequest());

        // Rejected at deserialization — the current password hash must be untouched.
        assertThat(userRepo.findById(testUser.getId()).orElseThrow().getPasswordHash())
            .isEqualTo(originalHash);
    }

    @Test
    void ac0221_unexpectedAccountIdField_returns400() throws Exception {
        mockMvc.perform(post(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s","newPassword":"NewPassword1!","accountId":"other-user"}
                    """.formatted(CURRENT_PASSWORD)))
            .andExpect(status().isBadRequest());
    }

    // ---------------- Password policy (US01.2.4) ----------------

    @Test
    void ac0221_weakNewPassword_returns400_beforeAnyPasswordCheck() throws Exception {
        mockMvc.perform(post(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s","newPassword":"weak"}
                    """.formatted(CURRENT_PASSWORD)))
            .andExpect(status().isBadRequest());
    }

    // ---------------- Authentication required ----------------

    @Test
    void ac0221_noAuthorizationHeader_isRejected() throws Exception {
        mockMvc.perform(post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s","newPassword":"NewPassword1!"}
                    """.formatted(CURRENT_PASSWORD)))
            .andExpect(status().is4xxClientError());
    }

    // ---------------- Rate limiting ----------------

    @Test
    void ac0221_rateLimit_returns429WithRetryAfter_andSameMessageAsWrongPassword_after5FailedAttempts() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(URL)
                    .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"currentPassword":"WrongPassword1!","newPassword":"NewPassword1!"}
                        """))
                .andExpect(status().isUnauthorized());
        }

        final String body = mockMvc.perform(post(URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"currentPassword":"%s","newPassword":"NewPassword1!"}
                    """.formatted(CURRENT_PASSWORD)))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists("Retry-After"))
            .andReturn().getResponse().getContentAsString();

        // Anti-enumeration: identical wording to the 401 "wrong current password" body —
        // an attacker cannot tell rate-limiting apart from a wrong password by content alone.
        assertThat(body).contains(WRONG_PASSWORD_MESSAGE);
        // Even with the correct password, the rate limit blocked the change.
        assertThat(userRepo.findById(testUser.getId()).orElseThrow().getPasswordHash())
            .isEqualTo(originalHash);
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
