package fr.pivot.auth.controller;

import fr.pivot.AbstractIntegrationTest;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.AuthMethod;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.repository.AccessTokenRepository;
import fr.pivot.auth.repository.UserRepository;
import fr.pivot.auth.service.TokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code GET/DELETE /api/account/sessions} (US02.2.3).
 *
 * <p>Full Spring context + real PostgreSQL (Testcontainers) + the real Spring Security filter
 * chain (via {@code springSecurity()}) — bearer tokens are issued through
 * {@link TokenService#issue} exactly as at login, and every request goes through the real
 * {@link fr.pivot.config.TokenAuthenticationFilter}. This exercises the full path the frontend
 * will use, including the ownership (cross-user → 404) and current-session (→ 403) guards.
 *
 * <p>Traceability (see {@code us-sessions-actives.md}):
 * <ul>
 *   <li>"GET retourne la liste ... isCurrent" — {@code list_*}</li>
 *   <li>"DELETE /{tokenId} vérifie l'appartenance ... 404 (pas 403)" — {@code deleteOne_crossUser404}</li>
 *   <li>"DELETE /{tokenId} retourne 403 si tokenId est la session courante" — {@code deleteOne_currentSession403}</li>
 *   <li>"DELETE révoque toutes les sessions sauf la courante" — {@code deleteAll_*}</li>
 * </ul>
 */
class SessionControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AccessTokenRepository tokenRepo;

    @Autowired
    private UserRepository userRepo;

    private MockMvc mockMvc;
    private User userAlice;
    private User userAdmin;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(springSecurity())
            .build();
        // Seeded by V2__test_seeds.sql (Flyway, profile test, tenant_id=1)
        userAlice = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "user@pivot.test")
            .orElseThrow(() -> new IllegalStateException("Test user 'user@pivot.test' not found"));
        userAdmin = userRepo.findByTenantIdAndEmailAndDeletedAtIsNull(1L, "admin@pivot.test")
            .orElseThrow(() -> new IllegalStateException("Test user 'admin@pivot.test' not found"));
    }

    @AfterEach
    void tearDown() {
        tokenRepo.deleteByUserId(userAlice.getId());
        tokenRepo.deleteByUserId(userAdmin.getId());
        SecurityContextHolder.clearContext();
    }

    private String issueToken(final User user, final String deviceName, final String ip) {
        return tokenService.issue(user, "fp-" + user.getId() + "-" + System.nanoTime(), deviceName,
            "Mozilla/5.0 (test)", ip, AuthMethod.PASSWORD, false).rawToken();
    }

    // ----------------------------------------------------------------
    // GET /api/account/sessions
    // ----------------------------------------------------------------

    @Test
    void list_returnsOwnActiveSessions_withCurrentFlagged_excludingOtherUsers() throws Exception {
        final String currentRaw = issueToken(userAlice, "Chrome sur Windows", "203.0.113.1");
        final String otherRaw = issueToken(userAlice, "Safari sur iPhone", "203.0.113.2");
        issueToken(userAdmin, "Firefox sur Linux", "198.51.100.1"); // must never appear

        mockMvc.perform(get("/api/account/sessions")
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[*].device").value(org.hamcrest.Matchers.containsInAnyOrder(
                "Chrome sur Windows", "Safari sur iPhone")))
            .andExpect(jsonPath("$[*].isCurrent").value(org.hamcrest.Matchers.containsInAnyOrder(true, false)));

        // Sanity: the "other" user's device label never leaks into Alice's list.
        mockMvc.perform(get("/api/account/sessions")
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(jsonPath("$[*].device").value(
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("Firefox sur Linux"))));

        assertThat(otherRaw).isNotBlank();
    }

    @Test
    void list_stripsHtmlFromDeviceName_inResponse() throws Exception {
        // TokenService already sanitizes at write time — this asserts the guarantee end-to-end.
        final String raw = issueToken(userAlice, "<img src=x onerror=alert(1)>Chrome", "203.0.113.1");

        mockMvc.perform(get("/api/account/sessions")
                .header("Authorization", "Bearer " + raw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].device").value("Chrome"))
            .andExpect(jsonPath("$[0].device", org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("<"))));
    }

    @Test
    void list_serializesTimestamps_asIso8601Strings() throws Exception {
        // Locks the frontend contract: createdAt/expiresAt are ISO-8601 strings (Jackson
        // default with JavaTimeModule), never epoch millis.
        final String raw = issueToken(userAlice, "Chrome", "203.0.113.1");

        mockMvc.perform(get("/api/account/sessions")
                .header("Authorization", "Bearer " + raw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].createdAt",
                org.hamcrest.Matchers.matchesPattern("^\\d{4}-\\d{2}-\\d{2}T.*Z$")))
            .andExpect(jsonPath("$[0].expiresAt",
                org.hamcrest.Matchers.matchesPattern("^\\d{4}-\\d{2}-\\d{2}T.*Z$")));
    }

    @Test
    void list_excludesRevokedSessions() throws Exception {
        final String revokedRaw = issueToken(userAlice, "Old device", "203.0.113.9");
        tokenService.revokeByRawToken(revokedRaw);
        final String currentRaw = issueToken(userAlice, "Chrome", "203.0.113.1");

        mockMvc.perform(get("/api/account/sessions")
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].isCurrent").value(true));
    }

    @Test
    void list_returns403_whenNoBearerToken() throws Exception {
        // No AuthenticationEntryPoint is configured (see SecurityConfig) — Spring Security's
        // default for an unauthenticated request denied by isAuthenticated() is 403, not 401,
        // consistent with every other authenticated endpoint in this app (e.g. ModuleController).
        mockMvc.perform(get("/api/account/sessions"))
            .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------
    // DELETE /api/account/sessions/{tokenId}
    // ----------------------------------------------------------------

    @Test
    void deleteOne_revokesOwnOtherSession_returns204() throws Exception {
        final String currentRaw = issueToken(userAlice, "Chrome", "203.0.113.1");
        final String otherRaw = issueToken(userAlice, "Safari", "203.0.113.2");
        final Long otherId = activeTokenId(otherRaw);

        mockMvc.perform(delete("/api/account/sessions/{tokenId}", otherId)
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isNoContent());

        final AccessToken revoked = tokenRepo.findById(otherId).orElseThrow();
        assertThat(revoked.getStatus()).isEqualTo(TokenStatus.REVOKED);
    }

    @Test
    void deleteOne_crossUser_returns404_andDoesNotRevoke() throws Exception {
        final String currentRaw = issueToken(userAlice, "Chrome", "203.0.113.1");
        final String adminRaw = issueToken(userAdmin, "Firefox", "198.51.100.1");
        final Long adminTokenId = activeTokenId(adminRaw);

        mockMvc.perform(delete("/api/account/sessions/{tokenId}", adminTokenId)
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isNotFound());

        final AccessToken untouched = tokenRepo.findById(adminTokenId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(TokenStatus.ACTIVE);
    }

    @Test
    void deleteOne_currentSession_returns403_andDoesNotRevoke() throws Exception {
        final String currentRaw = issueToken(userAlice, "Chrome", "203.0.113.1");
        final Long currentId = activeTokenId(currentRaw);

        mockMvc.perform(delete("/api/account/sessions/{tokenId}", currentId)
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isForbidden());

        final AccessToken untouched = tokenRepo.findById(currentId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(TokenStatus.ACTIVE);
    }

    @Test
    void deleteOne_unknownId_returns404() throws Exception {
        final String currentRaw = issueToken(userAlice, "Chrome", "203.0.113.1");

        mockMvc.perform(delete("/api/account/sessions/{tokenId}", 999_999_999L)
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteOne_returns403_whenNoBearerToken() throws Exception {
        mockMvc.perform(delete("/api/account/sessions/{tokenId}", 1L))
            .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------
    // DELETE /api/account/sessions (all except current)
    // ----------------------------------------------------------------

    @Test
    void deleteAll_revokesOthers_keepsCurrentActive_returns204() throws Exception {
        final String currentRaw = issueToken(userAlice, "Chrome", "203.0.113.1");
        final String otherRaw1 = issueToken(userAlice, "Safari", "203.0.113.2");
        final String otherRaw2 = issueToken(userAlice, "Edge", "203.0.113.3");
        final Long currentId = activeTokenId(currentRaw);
        final Long otherId1 = activeTokenId(otherRaw1);
        final Long otherId2 = activeTokenId(otherRaw2);

        mockMvc.perform(delete("/api/account/sessions")
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isNoContent());

        assertThat(tokenRepo.findById(currentId).orElseThrow().getStatus()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(tokenRepo.findById(otherId1).orElseThrow().getStatus()).isEqualTo(TokenStatus.REVOKED);
        assertThat(tokenRepo.findById(otherId2).orElseThrow().getStatus()).isEqualTo(TokenStatus.REVOKED);
    }

    @Test
    void deleteAll_doesNotAffectOtherUsersSessions() throws Exception {
        final String currentRaw = issueToken(userAlice, "Chrome", "203.0.113.1");
        final String adminRaw = issueToken(userAdmin, "Firefox", "198.51.100.1");
        final Long adminTokenId = activeTokenId(adminRaw);

        mockMvc.perform(delete("/api/account/sessions")
                .header("Authorization", "Bearer " + currentRaw))
            .andExpect(status().isNoContent());

        assertThat(tokenRepo.findById(adminTokenId).orElseThrow().getStatus()).isEqualTo(TokenStatus.ACTIVE);
    }

    @Test
    void deleteAll_returns403_whenNoBearerToken() throws Exception {
        mockMvc.perform(delete("/api/account/sessions"))
            .andExpect(status().isForbidden());
    }

    private Long activeTokenId(final String rawToken) {
        return tokenService.validate(rawToken).orElseThrow().getId();
    }
}
