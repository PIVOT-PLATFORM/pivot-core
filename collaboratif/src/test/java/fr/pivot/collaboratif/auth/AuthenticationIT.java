package fr.pivot.collaboratif.auth;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport.AuthFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for real bearer-token authentication (EN08.3, closes #45): {@link
 * fr.pivot.collaboratif.context.CollaboratifRequestPrincipalResolver} resolving a validated {@link
 * fr.pivot.collaboratif.context.CollaboratifRequestPrincipal} via {@link
 * fr.pivot.collaboratif.testsupport.auth.TestAuthenticatedPrincipalResolver} (this module's
 * isolated-test double for {@link fr.pivot.core.auth.AuthenticatedPrincipalResolver} — production
 * duplication removed EN53.2 Vague 2, see that class's Javadoc) against a real PostgreSQL {@code
 * public.access_tokens}/{@code public.users}/{@code public.tenants} schema, exercised end-to-end
 * over HTTP through {@link AuthProbeTestController}.
 *
 * <p>Covers every acceptance criterion of issue #45: valid token resolves the correct
 * userId/tenantId; expired/revoked/unknown token, deactivated tenant, deactivated user, missing
 * header, and malformed header all collapse to a generic HTTP 401 with no discriminating detail.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationIT extends AbstractCollaboratifIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    private String dbUser() {
        return postgres.getUsername();
    }

    private String dbPassword() {
        return postgres.getPassword();
    }

    /**
     * Given a valid, active, non-expired token, when the Authorization header carries it,
     * then the request resolves HTTP 200 with the correct userId/tenantId.
     */
    @Test
    void validToken_resolvesCorrectPrincipal() throws Exception {
        AuthFixture fixture = PlatformAuthTestSupport.seedActiveUserWithToken(jdbcUrl(), dbUser(), dbPassword());

        mockMvc().perform(get("/test/auth/whoami").header("Authorization", fixture.authorizationHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(fixture.userId()))
                .andExpect(jsonPath("$.tenantId").value(fixture.tenantId()));
    }

    /**
     * Error case: given a token whose {@code expires_at} is in the past, then the request
     * returns HTTP 401.
     */
    @Test
    void expiredToken_returns401() throws Exception {
        long tenantId = PlatformAuthTestSupport.seedTenant(jdbcUrl(), dbUser(), dbPassword(), null);
        long userId = PlatformAuthTestSupport.seedUser(jdbcUrl(), dbUser(), dbPassword(), tenantId, true);
        String token = PlatformAuthTestSupport.issueToken(
                jdbcUrl(), dbUser(), dbPassword(), userId, "active", Instant.now().minusSeconds(60));

        mockMvc().perform(get("/test/auth/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Error case: given a token whose status is {@code revoked}, then the request returns
     * HTTP 401.
     */
    @Test
    void revokedToken_returns401() throws Exception {
        long tenantId = PlatformAuthTestSupport.seedTenant(jdbcUrl(), dbUser(), dbPassword(), null);
        long userId = PlatformAuthTestSupport.seedUser(jdbcUrl(), dbUser(), dbPassword(), tenantId, true);
        String token = PlatformAuthTestSupport.issueToken(
                jdbcUrl(), dbUser(), dbPassword(), userId, "revoked", Instant.now().plusSeconds(3600));

        mockMvc().perform(get("/test/auth/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Error case: given a syntactically-valid-looking but never-issued token, then the request
     * returns HTTP 401.
     */
    @Test
    void unknownToken_returns401() throws Exception {
        mockMvc().perform(get("/test/auth/whoami")
                        .header("Authorization", "Bearer " + "0".repeat(64)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Error case: given a valid token whose tenant was deactivated after the token was issued
     * ({@code tenant_invalidation_timestamp} set later than {@code created_at}), then the
     * request returns HTTP 401 (bulk tenant revocation, ADR-022).
     */
    @Test
    void tenantDeactivatedAfterTokenIssued_returns401() throws Exception {
        long tenantId = PlatformAuthTestSupport.seedTenant(jdbcUrl(), dbUser(), dbPassword(), null);
        long userId = PlatformAuthTestSupport.seedUser(jdbcUrl(), dbUser(), dbPassword(), tenantId, true);
        String token = PlatformAuthTestSupport.issueToken(
                jdbcUrl(), dbUser(), dbPassword(), userId, "active", Instant.now().plusSeconds(3600));

        // Deactivate the tenant strictly after the token's created_at (now()).
        Thread.sleep(1_100); // ensure the invalidation timestamp below is strictly after created_at
        invalidateTenant(tenantId, Instant.now());

        mockMvc().perform(get("/test/auth/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Error case: given a valid token whose user has {@code is_active = false}, then the
     * request returns HTTP 401.
     */
    @Test
    void deactivatedUser_returns401() throws Exception {
        long tenantId = PlatformAuthTestSupport.seedTenant(jdbcUrl(), dbUser(), dbPassword(), null);
        long userId = PlatformAuthTestSupport.seedUser(jdbcUrl(), dbUser(), dbPassword(), tenantId, false);
        String token = PlatformAuthTestSupport.issueToken(
                jdbcUrl(), dbUser(), dbPassword(), userId, "active", Instant.now().plusSeconds(3600));

        mockMvc().perform(get("/test/auth/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Error case: given no Authorization header at all, then the request returns HTTP 401.
     */
    @Test
    void missingAuthorizationHeader_returns401() throws Exception {
        mockMvc().perform(get("/test/auth/whoami"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Error case: given an Authorization header without the "Bearer " prefix, then the request
     * returns HTTP 401.
     */
    @Test
    void malformedAuthorizationHeader_returns401() throws Exception {
        mockMvc().perform(get("/test/auth/whoami").header("Authorization", "Token abc123"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Given a valid token, when the Authorization header uses lowercase "bearer ", then the
     * request still resolves HTTP 200 (case-insensitive prefix per AC).
     */
    @Test
    void lowercaseBearerPrefix_stillResolves() throws Exception {
        AuthFixture fixture = PlatformAuthTestSupport.seedActiveUserWithToken(jdbcUrl(), dbUser(), dbPassword());

        mockMvc().perform(get("/test/auth/whoami").header("Authorization", "bearer " + fixture.rawToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(fixture.userId()));
    }

    /**
     * Marks a tenant's {@code tenant_invalidation_timestamp} — test-only direct SQL, mirrors
     * the bulk-revocation write {@code pivot-core}'s own admin flow performs.
     *
     * @param tenantId  the tenant to deactivate
     * @param timestamp the invalidation timestamp
     */
    private void invalidateTenant(final long tenantId, final Instant timestamp) throws Exception {
        try (var conn = java.sql.DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
                var ps = conn.prepareStatement(
                        "UPDATE public.tenants SET tenant_invalidation_timestamp = ? WHERE id = ?")) {
            ps.setObject(1, java.time.OffsetDateTime.ofInstant(timestamp, java.time.ZoneOffset.UTC));
            ps.setLong(2, tenantId);
            ps.executeUpdate();
        }
    }
}
