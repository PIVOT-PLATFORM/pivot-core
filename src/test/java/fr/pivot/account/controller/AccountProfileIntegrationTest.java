package fr.pivot.account.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests d'intégration (PostgreSQL via Testcontainers, contexte Spring réel, dispatch HTTP
 * complet via MockMvc + le filtre {@code TokenAuthenticationFilter} réel) pour
 * {@link AccountController} (US02.1.1).
 *
 * <p>Traçabilité :
 * <ul>
 *   <li>"GET /api/account/profile retourne prénom/nom/email/avatar" — {@code ac0211_01_*}</li>
 *   <li>"PATCH met à jour prénom et nom" — {@code ac0211_02_*}</li>
 *   <li>Security "PATCH rejette le champ email avec 400" — {@code ac0211_sec_email_*}</li>
 *   <li>"strip HTML côté backend (XSS)" — {@code ac0211_xss_*}</li>
 *   <li>Error case "prénom/nom obligatoires" — {@code ac0211_err_*}</li>
 *   <li>"Upload avatar stocké, URL retournée" + formats/tailles — {@code ac0211_avatar_*}</li>
 *   <li>Security "identité exclusivement depuis le token porteur" — {@code ac0211_sec_auth_*}</li>
 * </ul>
 */
class AccountProfileIntegrationTest extends AbstractIntegrationTest {

    private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02, 0x03};

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccessTokenRepository tokenRepository;

    private MockMvc mockMvc;
    private User testUser;
    private String rawToken;
    private String originalFirstName;
    private String originalLastName;
    private String originalAvatarUrl;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();

        testUser = userRepository.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@pivot.test")
                .orElseThrow(() -> new IllegalStateException(
                        "Test user 'user@pivot.test' not found — verify Flyway applied db/seeds (profile test)"));
        originalFirstName = testUser.getFirstName();
        originalLastName = testUser.getLastName();
        originalAvatarUrl = testUser.getAvatarUrl();

        rawToken = tokenService.issue(testUser, "fp-account-it", "Chrome", "ua", "127.0.0.1",
                AuthMethod.PASSWORD, false).rawToken();
    }

    @AfterEach
    void tearDown() {
        tokenRepository.deleteByUserId(testUser.getId());
        // Restore the seeded user's original state so other tests relying on the same seed
        // (e.g. TokenServiceIntegrationTest) are unaffected by mutations performed here.
        final User fresh = userRepository.findById(testUser.getId()).orElseThrow();
        fresh.setFirstName(originalFirstName);
        fresh.setLastName(originalLastName);
        fresh.setAvatarUrl(originalAvatarUrl);
        userRepository.save(fresh);
    }

    // ----------------------------------------------------------------
    // GET /account/profile
    // ----------------------------------------------------------------

    @Test
    void ac0211_01_getProfile_returnsSeededUserData() throws Exception {
        mockMvc.perform(get("/account/profile").header("Authorization", "Bearer " + rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value(originalFirstName))
                .andExpect(jsonPath("$.lastName").value(originalLastName))
                .andExpect(jsonPath("$.email").value("user@pivot.test"));
    }

    @Test
    void ac0211_avatarNull_getProfile_returnsNullAvatar_whenNoneSet() throws Exception {
        mockMvc.perform(get("/account/profile").header("Authorization", "Bearer " + rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());
    }

    // Note: with no valid bearer token, the request never reaches AccountController — it is
    // rejected earlier by Spring Security's authorization layer (anyRequest().authenticated()
    // in SecurityConfig, no custom AuthenticationEntryPoint configured). The framework's default
    // behaviour for a denied unauthenticated request is 403, not 401 — this is inherent to the
    // shared SecurityConfig (identical for every protected endpoint in the app, not specific to
    // this controller) and is exercised here, not asserted as a new contract. AccountController's
    // own 401 branch (resolveUser() returning null) is a defensive path for an authenticated-but-
    // malformed security context, covered at the unit level in AccountControllerTest — it is not
    // reachable from a real HTTP request with no/invalid token.

    @Test
    void ac0211_sec_auth_getProfile_returns403_whenNoToken() throws Exception {
        mockMvc.perform(get("/account/profile"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ac0211_sec_auth_getProfile_returns403_whenTokenInvalid() throws Exception {
        mockMvc.perform(get("/account/profile").header("Authorization", "Bearer invalid-token-value"))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------
    // PATCH /account/profile
    // ----------------------------------------------------------------

    @Test
    void ac0211_02_patchProfile_updatesFirstNameAndLastName() throws Exception {
        mockMvc.perform(patch("/account/profile")
                        .header("Authorization", "Bearer " + rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Alicia\",\"lastName\":\"Martinez\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Alicia"))
                .andExpect(jsonPath("$.lastName").value("Martinez"));

        final User reloaded = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(reloaded.getFirstName()).isEqualTo("Alicia");
        assertThat(reloaded.getLastName()).isEqualTo("Martinez");
    }

    @Test
    void ac0211_sec_email_patchProfile_rejectsWithEmailField_returns400() throws Exception {
        mockMvc.perform(patch("/account/profile")
                        .header("Authorization", "Bearer " + rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Alicia\",\"lastName\":\"Martinez\",\"email\":\"hacker@evil.test\"}"))
                .andExpect(status().isBadRequest());

        final User reloaded = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("user@pivot.test");
        assertThat(reloaded.getFirstName()).isEqualTo(originalFirstName);
    }

    @Test
    void ac0211_sec_email_patchProfile_rejectsWithEmailFieldDifferentCasing_returns400() throws Exception {
        mockMvc.perform(patch("/account/profile")
                        .header("Authorization", "Bearer " + rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Alicia\",\"lastName\":\"Martinez\",\"Email\":\"hacker@evil.test\"}"))
                .andExpect(status().isBadRequest());

        final User reloaded = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("user@pivot.test");
        assertThat(reloaded.getFirstName()).isEqualTo(originalFirstName);
    }

    @Test
    void ac0211_xss_patchProfile_stripsHtmlFromNames() throws Exception {
        mockMvc.perform(patch("/account/profile")
                        .header("Authorization", "Bearer " + rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"<script>alert(1)</script>Bob\",\"lastName\":\"<b>Dupont</b>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("alert(1)Bob"))
                .andExpect(jsonPath("$.lastName").value("Dupont"));
    }

    @Test
    void ac0211_err_patchProfile_rejectsBlankFirstName_returns400() throws Exception {
        mockMvc.perform(patch("/account/profile")
                        .header("Authorization", "Bearer " + rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"\",\"lastName\":\"Dupont\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ac0211_err_patchProfile_rejectsNameOver100Chars_returns400() throws Exception {
        final String tooLong = "A".repeat(101);
        mockMvc.perform(patch("/account/profile")
                        .header("Authorization", "Bearer " + rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"" + tooLong + "\",\"lastName\":\"Dupont\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ac0211_sec_auth_patchProfile_returns403_whenNoToken() throws Exception {
        // See comment above ac0211_sec_auth_getProfile_returns403_whenNoToken.
        mockMvc.perform(patch("/account/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Bob\",\"lastName\":\"Dupont\"}"))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------
    // POST /account/profile/avatar
    // ----------------------------------------------------------------

    @Test
    void ac0211_avatar_uploadJpeg_returns200WithStoredUrl() throws Exception {
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", JPEG_BYTES);

        mockMvc.perform(multipart("/account/profile/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(
                        org.hamcrest.Matchers.matchesPattern("/api/avatars/1/[0-9a-f-]+\\.jpg")));

        final User reloaded = userRepository.findById(testUser.getId()).orElseThrow();
        assertThat(reloaded.getAvatarUrl()).matches("/api/avatars/1/[0-9a-f-]+\\.jpg");
    }

    @Test
    void ac0211_avatar_uploadedFileIsServedByStaticResourceRoute() throws Exception {
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", JPEG_BYTES);

        final String body = mockMvc.perform(multipart("/account/profile/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        final String avatarUrl = com.jayway.jsonpath.JsonPath.read(body, "$.avatarUrl");
        // The DTO carries the full external-facing path (including the /api context-path
        // segment) — MockMvc's webAppContextSetup does not apply a context path, so strip it
        // before dispatching, matching how other IT tests here hit controller paths directly.
        final String pathWithoutContext = avatarUrl.replaceFirst("^/api", "");

        mockMvc.perform(get(pathWithoutContext))
                .andExpect(status().isOk());
    }

    @Test
    void ac0211_avatar_err_rejectsInvalidFormat_returns400() throws Exception {
        final MockMultipartFile file =
                new MockMultipartFile("file", "doc.pdf", "application/pdf", "%PDF-1.4".getBytes());

        mockMvc.perform(multipart("/account/profile/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AVATAR_INVALID_FORMAT"));
    }

    @Test
    void ac0211_avatar_err_rejectsFileOver2Mb_returns400() throws Exception {
        final byte[] tooLarge = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(JPEG_BYTES, 0, tooLarge, 0, JPEG_BYTES.length);
        final MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", tooLarge);

        mockMvc.perform(multipart("/account/profile/avatar")
                        .file(file)
                        .header("Authorization", "Bearer " + rawToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AVATAR_TOO_LARGE"));
    }

    @Test
    void ac0211_sec_auth_uploadAvatar_returns403_whenNoToken() throws Exception {
        // See comment above ac0211_sec_auth_getProfile_returns403_whenNoToken.
        final MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", JPEG_BYTES);

        mockMvc.perform(multipart("/account/profile/avatar").file(file))
                .andExpect(status().isForbidden());
    }
}
