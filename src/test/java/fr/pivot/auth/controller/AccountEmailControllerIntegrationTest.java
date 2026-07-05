package fr.pivot.auth.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.EmailChangeRequest;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.EmailChangeRequestRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.EmailService;
import fr.pivot.auth.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code POST /account/email} and {@code GET /account/email/confirm}
 * (US02.2.2) — full Spring context, real PostgreSQL (Testcontainers, inherited from
 * {@link AbstractIntegrationTest}) and real Redis (Testcontainers, {@link #REDIS}) so the
 * sliding-window rate limiter genuinely exercises Redis.
 *
 * <p>The real {@link EmailService} is replaced by a Mockito mock — no SMTP server is available
 * in this test environment, mirroring the existing convention in this codebase (see
 * {@code AccountControllerIntegrationTest} for US02.2.1).
 *
 * <p>Traceability (US02.2.2 AC table):
 * <ul>
 *   <li>"lien de confirmation vers la nouvelle adresse" + "ancien email conservé jusqu'à
 *       confirmation" — {@code ac0222_requestChange_happyPath_*}, {@code ac0222_confirm_happyPath_*}</li>
 *   <li>"toujours 202, doublon jamais exposé dans le body" — {@code ac0222_requestChange_duplicate_*}</li>
 *   <li>"mot de passe actuel requis" — {@code ac0222_requestChange_wrongPassword_*}</li>
 *   <li>"rate limit 3/heure par userId → 429" — {@code ac0222_requestChange_rateLimit_*}</li>
 *   <li>"second clic → 410 Gone" — {@code ac0222_confirm_secondClick_*}</li>
 *   <li>"nouvelle demande annule la précédente" — {@code ac0222_requestChange_cancelsPrevious_*}</li>
 *   <li>"connexion avec le nouvel email avant confirmation → 401" — {@code ac0222_loginWithUnconfirmedNewEmail_*}</li>
 * </ul>
 */
@Import(AccountEmailControllerIntegrationTest.TestMailConfig.class)
class AccountEmailControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String REQUEST_URL = "/account/email";
    private static final String CONFIRM_URL = "/account/email/confirm";
    private static final String LOGIN_URL = "/auth/login";
    private static final String TEST_USER_EMAIL = "user@pivot.test";
    /** Seeded in V2__test_seeds.sql for every test account, including {@link #TEST_USER_EMAIL}. */
    private static final String CURRENT_PASSWORD = "Pivot@Test123!";
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
    @Autowired private EmailChangeRequestRepository emailChangeRepo;
    @Autowired private AccessTokenRepository tokenRepo;
    @Autowired private TokenService tokenService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private EmailService emailService;

    private MockMvc mockMvc;
    private User testUser;
    private String rawToken;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity())
            .build();

        testUser = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, TEST_USER_EMAIL)
            .orElseThrow(() -> new IllegalStateException(
                "Test user 'user@pivot.test' not found — verify Flyway applied db/seeds (profile test)"));
        rawToken = tokenService.issue(
            testUser, null, null, "JUnit", "127.0.0.1", AuthMethod.PASSWORD, false).rawToken();

        reset(emailService);
    }

    @AfterEach
    void tearDown() {
        // Restore the seeded email so other test methods keep authenticating with
        // TEST_USER_EMAIL regardless of execution order.
        final User user = userRepo.findById(testUser.getId()).orElseThrow();
        user.setEmail(TEST_USER_EMAIL);
        userRepo.save(user);
        emailChangeRepo.deleteAll(emailChangeRepo.findAllByUserId(testUser.getId()));
        tokenRepo.deleteByUserId(testUser.getId());
        clearRateLimitKeys();
        SecurityContextHolder.clearContext();
    }

    private void clearRateLimitKeys() {
        final Set<String> keys = redis.keys("rl:email-change*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String requestBody(final String newEmail, final String password) {
        return """
            {"newEmail":"%s","currentPassword":"%s"}
            """.formatted(newEmail, password);
    }

    // ---------------- POST /account/email — happy path ----------------

    @Test
    void ac0222_requestChange_happyPath_returns202_sendsConfirmationToNewAddress_leavesOldEmailActive() throws Exception {
        final String body = mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("newaddr@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).isBlank();

        verify(emailService).sendEmailChangeConfirmationEmail(
            eq("newaddr@pivot.test"), anyString(), anyString(), any());
        verify(emailService, never()).sendEmailChangeDuplicateEmail(any(), any(), any());

        // Old address remains the account's active login identity until confirmation.
        assertThat(userRepo.findById(testUser.getId()).orElseThrow().getEmail())
            .isEqualTo(TEST_USER_EMAIL);
        assertThat(emailChangeRepo.findAllByUserId(testUser.getId())).hasSize(1);
    }

    // ---------------- POST /account/email — wrong current password ----------------

    @Test
    void ac0222_requestChange_wrongPassword_returns401_createsNoRequest() throws Exception {
        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("newaddr@pivot.test", "WrongPassword1!")))
            .andExpect(status().isUnauthorized());

        assertThat(emailChangeRepo.findAllByUserId(testUser.getId())).isEmpty();
        verify(emailService, never()).sendEmailChangeConfirmationEmail(any(), any(), any(), any());
    }

    // ---------------- POST /account/email — anti-enumeration on duplicate ----------------

    @Test
    void ac0222_requestChange_duplicateTarget_stillReturns202_noticeOnlySentByEmail_noDbRowCreated() throws Exception {
        // admin@pivot.test already exists (seeded) — the anti-enumeration AC requires an
        // identical 202 response whether or not the target address is taken.
        final String body = mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("admin@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).isBlank();
        verify(emailService).sendEmailChangeDuplicateEmail(eq("admin@pivot.test"), anyString(), any());
        verify(emailService, never()).sendEmailChangeConfirmationEmail(any(), any(), any(), any());
        assertThat(emailChangeRepo.findAllByUserId(testUser.getId())).isEmpty();
    }

    // ---------------- POST /account/email — mass-assignment / unexpected field ----------------

    @Test
    void ac0222_requestChange_unexpectedUserIdField_isIgnoredOrRejected_neverAppliedAsIdentity() throws Exception {
        // No matter how Jackson handles the unknown field, the identity used must remain the
        // bearer-token user (testUser), never the spoofed id — asserted via the DB side effect.
        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"newEmail":"spoofed@pivot.test","currentPassword":"%s","userId":999}
                    """.formatted(CURRENT_PASSWORD)))
            .andExpect(status().is2xxSuccessful());

        final java.util.List<EmailChangeRequest> requests = emailChangeRepo.findAllByUserId(testUser.getId());
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getUser().getId()).isEqualTo(testUser.getId());
    }

    // ---------------- POST /account/email — authentication required ----------------

    @Test
    void ac0222_requestChange_noAuthorizationHeader_isRejected() throws Exception {
        mockMvc.perform(post(REQUEST_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("newaddr@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().is4xxClientError());
    }

    // ---------------- POST /account/email — rate limiting ----------------

    @Test
    void ac0222_requestChange_rateLimit_returns429_after3AttemptsPerHour() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post(REQUEST_URL)
                    .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody("attempt" + i + "@pivot.test", CURRENT_PASSWORD)))
                .andExpect(status().isAccepted());
        }

        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("oneMore@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // ---------------- New request cancels the previous one ----------------

    @Test
    void ac0222_requestChange_cancelsPrevious_firstTokenBecomesGone_secondStillWorks() throws Exception {
        final ArgumentCaptor<String> firstToken = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("first@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isAccepted());
        verify(emailService).sendEmailChangeConfirmationEmail(eq("first@pivot.test"), anyString(), firstToken.capture(), any());

        final ArgumentCaptor<String> secondToken = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("second@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isAccepted());
        verify(emailService).sendEmailChangeConfirmationEmail(eq("second@pivot.test"), anyString(), secondToken.capture(), any());

        mockMvc.perform(get(CONFIRM_URL).param("token", firstToken.getValue()))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_ALREADY_USED"));

        mockMvc.perform(get(CONFIRM_URL).param("token", secondToken.getValue()))
            .andExpect(status().isOk());

        assertThat(userRepo.findById(testUser.getId()).orElseThrow().getEmail())
            .isEqualTo("second@pivot.test");
    }

    // ---------------- GET /account/email/confirm — happy path ----------------

    @Test
    void ac0222_confirm_happyPath_returns200_updatesEmail_notifiesOldAddress() throws Exception {
        final ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("confirmed@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isAccepted());
        verify(emailService).sendEmailChangeConfirmationEmail(eq("confirmed@pivot.test"), anyString(), tokenCaptor.capture(), any());

        mockMvc.perform(get(CONFIRM_URL).param("token", tokenCaptor.getValue()))
            .andExpect(status().isOk());

        assertThat(userRepo.findById(testUser.getId()).orElseThrow().getEmail())
            .isEqualTo("confirmed@pivot.test");
        verify(emailService).sendEmailChangedNotificationEmail(
            eq(TEST_USER_EMAIL), anyString(), eq(TEST_USER_EMAIL), eq("confirmed@pivot.test"),
            any(Instant.class), anyString(), any(Locale.class));
    }

    // ---------------- GET /account/email/confirm — invalid / expired / used ----------------

    @Test
    void ac0222_confirm_unknownToken_returns400_withInvalidCode() throws Exception {
        mockMvc.perform(get(CONFIRM_URL).param("token", "does-not-exist"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_INVALID"));
    }

    @Test
    void ac0222_confirm_expiredToken_returns400_withExpiredCode() throws Exception {
        final ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("expiring@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isAccepted());
        verify(emailService).sendEmailChangeConfirmationEmail(eq("expiring@pivot.test"), anyString(), tokenCaptor.capture(), any());

        final EmailChangeRequest pending = emailChangeRepo.findAllByUserId(testUser.getId()).get(0);
        pending.setExpiresAt(Instant.now().minusSeconds(60));
        emailChangeRepo.save(pending);

        mockMvc.perform(get(CONFIRM_URL).param("token", tokenCaptor.getValue()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_EXPIRED"));

        assertThat(userRepo.findById(testUser.getId()).orElseThrow().getEmail()).isEqualTo(TEST_USER_EMAIL);
    }

    @Test
    void ac0222_confirm_secondClick_returns410Gone() throws Exception {
        final ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("onceonly@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isAccepted());
        verify(emailService).sendEmailChangeConfirmationEmail(eq("onceonly@pivot.test"), anyString(), tokenCaptor.capture(), any());

        mockMvc.perform(get(CONFIRM_URL).param("token", tokenCaptor.getValue()))
            .andExpect(status().isOk());

        mockMvc.perform(get(CONFIRM_URL).param("token", tokenCaptor.getValue()))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_TOKEN_ALREADY_USED"));
    }

    // ---------------- Login with the new, unconfirmed email ----------------

    @Test
    void ac0222_loginWithUnconfirmedNewEmail_returns401() throws Exception {
        mockMvc.perform(post(REQUEST_URL)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + rawToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody("notyetactive@pivot.test", CURRENT_PASSWORD)))
            .andExpect(status().isAccepted());

        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"notyetactive@pivot.test","password":"%s"}
                    """.formatted(CURRENT_PASSWORD)))
            .andExpect(status().isUnauthorized());

        // The old address, in contrast, still logs in normally.
        mockMvc.perform(post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"%s","password":"%s"}
                    """.formatted(TEST_USER_EMAIL, CURRENT_PASSWORD)))
            .andExpect(status().isOk());
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
