package fr.pivot.config;

import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.TokenStatus;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TokenAuthenticationFilter}.
 *
 * <p>Verifies Bearer extraction, SecurityContext population, auto-rotation
 * header/cookie setting, and Optional-based race condition path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenAuthenticationFilterTest {

    @Mock private TokenService tokenService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;
    @Mock private User user;

    private TokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TokenAuthenticationFilter(tokenService, new CookieHelper("pivot_session", false));
        SecurityContextHolder.clearContext();
        when(user.getEmail()).thenReturn("alice@pivot.app");
        when(user.getRole()).thenReturn("ROLE_USER");
    }

    @AfterEach
    void tearDown() {
        // Several tests above call filter.doFilterInternal() with a valid mocked token, which
        // populates the (static, thread-local) SecurityContextHolder via the real
        // authenticateRequest() code path — with a Mockito @Mock User whose getTenant() is
        // unstubbed (returns null). Without this cleanup, that polluted context survives past
        // this class (Surefire reuses the same JVM/thread for all test classes) and can leak
        // into a *later* test class's real HTTP requests. Concretely: it was leaking into
        // AccountProfileIntegrationTest#ac0211_sec_auth_uploadAvatar_returns403_whenNoToken,
        // which sends no Authorization header at all and expects Spring Security to deny with
        // 403 before any controller/service code runs — instead it observed the leftover mock
        // User (tenant == null) as if it were an authenticated principal, reached
        // ProfileService#updateAvatar, and threw an NPE on user.getTenant().getId() instead of
        // ever getting a 403. See the sibling *IntegrationTest classes in this codebase
        // (AdminModuleActivationIntegrationTest, ModuleStatusEndpointIntegrationTest) which
        // follow the same clearContext()-in-@AfterEach convention for the same reason.
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------
    // No token — pass-through
    // ----------------------------------------------------------------

    @Test
    void doFilter_noAuthHeader_chainContinues_noAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(tokenService, never()).validate(anyString());
    }

    @Test
    void doFilter_malformedAuthHeader_noBearer_chainContinues() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, chain);

        verify(tokenService, never()).validate(anyString());
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_bearerWithBlankToken_chainContinues() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer   ");

        filter.doFilterInternal(request, response, chain);

        verify(tokenService, never()).validate(anyString());
        verify(chain).doFilter(request, response);
    }

    // ----------------------------------------------------------------
    // Valid token — authentication set
    // ----------------------------------------------------------------

    @Test
    void doFilter_validToken_populatesSecurityContext() throws Exception {
        final String raw = "valid-raw-token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + raw);
        when(tokenService.validate(raw)).thenReturn(Optional.of(activeToken()));
        when(tokenService.getRefreshThreshold()).thenReturn(0.5);

        filter.doFilterInternal(request, response, chain);

        final var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alice@pivot.app");
        assertThat(auth.getAuthorities()).extracting(Object::toString).contains("ROLE_USER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_validToken_setsCurrentTokenIdRequestAttribute() throws Exception {
        // US02.2.3: SessionController reads this attribute instead of re-validating the same
        // bearer token a second time against the database.
        final String raw = "valid-raw-token";
        final AccessToken token = activeToken();
        ReflectionTestUtils.setField(token, "id", 42L);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + raw);
        when(tokenService.validate(raw)).thenReturn(Optional.of(token));
        when(tokenService.getRefreshThreshold()).thenReturn(0.5);

        filter.doFilterInternal(request, response, chain);

        verify(request).setAttribute(TokenAuthenticationFilter.CURRENT_TOKEN_ID_ATTRIBUTE, 42L);
    }

    @Test
    void doFilter_noToken_neverSetsCurrentTokenIdRequestAttribute() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(request, never()).setAttribute(Mockito.eq(TokenAuthenticationFilter.CURRENT_TOKEN_ID_ATTRIBUTE), any());
    }

    @Test
    void doFilter_validToken_noRefreshNeeded_noXNewTokenHeader() throws Exception {
        final String raw = "fresh-token";
        final AccessToken token = activeToken(); // 2000/3600 = 55% remaining, above 50% threshold
        when(request.getHeader("Authorization")).thenReturn("Bearer " + raw);
        when(tokenService.validate(raw)).thenReturn(Optional.of(token));
        when(tokenService.getRefreshThreshold()).thenReturn(0.5);

        filter.doFilterInternal(request, response, chain);

        verify(response, never()).setHeader(Mockito.eq("X-New-Token"), anyString());
        verify(tokenService, never()).rotate(any());
    }

    // ----------------------------------------------------------------
    // Token rotation
    // ----------------------------------------------------------------

    @Test
    void doFilter_tokenNeedsRefresh_setsXNewTokenHeader() throws Exception {
        final String raw = "stale-token";
        final AccessToken stale = soonExpiringToken(); // 100s remaining / 3600s TTL < 50%
        final TokenService.TokenIssueResult newIssue =
            new TokenService.TokenIssueResult("new-raw-token", Instant.now().plusSeconds(3600), 3600);

        when(request.getHeader("Authorization")).thenReturn("Bearer " + raw);
        when(tokenService.validate(raw)).thenReturn(Optional.of(stale));
        when(tokenService.getRefreshThreshold()).thenReturn(0.5);
        when(tokenService.rotate(stale)).thenReturn(Optional.of(newIssue));

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader("X-New-Token", "new-raw-token");
        verify(response).setHeader(Mockito.eq("X-Token-Expires-At"), anyString());
        verify(response).addCookie(any());
        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_rotationReturnsEmpty_noHeaderSet_authStillValid() throws Exception {
        // Race condition: token was already revoked by concurrent request
        final String raw = "race-token";
        final AccessToken stale = soonExpiringToken();

        when(request.getHeader("Authorization")).thenReturn("Bearer " + raw);
        when(tokenService.validate(raw)).thenReturn(Optional.of(stale));
        when(tokenService.getRefreshThreshold()).thenReturn(0.5);
        when(tokenService.rotate(stale)).thenReturn(Optional.empty()); // concurrent revoke

        filter.doFilterInternal(request, response, chain);

        // Auth still set from the validate() call above rotate()
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(response, never()).setHeader(Mockito.eq("X-New-Token"), anyString());
        verify(chain).doFilter(request, response);
    }

    // ----------------------------------------------------------------
    // Invalid / expired token
    // ----------------------------------------------------------------

    @Test
    void doFilter_invalidToken_noAuthSet_chainContinues() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
        when(tokenService.validate("bad-token")).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private AccessToken activeToken() {
        final AccessToken token = new AccessToken();
        token.setUser(user);
        token.setStatus(TokenStatus.ACTIVE);
        token.setTtlSeconds(3600);
        token.setExpiresAt(Instant.now().plusSeconds(2000)); // 55% remaining — above threshold
        return token;
    }

    private AccessToken soonExpiringToken() {
        final AccessToken token = new AccessToken();
        token.setUser(user);
        token.setStatus(TokenStatus.ACTIVE);
        token.setTtlSeconds(3600);
        token.setExpiresAt(Instant.now().plusSeconds(100)); // 2.7% remaining — below 50% threshold
        return token;
    }
}
