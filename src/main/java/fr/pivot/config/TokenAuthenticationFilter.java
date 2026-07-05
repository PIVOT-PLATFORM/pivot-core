package fr.pivot.config;

import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security filter that validates opaque session tokens on every request (US-AUTH-002).
 *
 * <p>Replaces the {@code oauth2ResourceServer().jwt()} configuration removed from
 * {@link SecurityConfig}. No JWT secret or external JWKS endpoint is required.
 *
 * <p>Authentication flow per request:
 * <ol>
 *   <li>Extract raw token from {@code Authorization: Bearer <token>} header.</li>
 *   <li>Delegate validation to {@link TokenService#validate(String)} (DB lookup by SHA-256).</li>
 *   <li>If valid: populate {@link SecurityContextHolder} with the user's authorities.</li>
 *   <li>If refresh threshold is crossed: rotate the token and send the new value via
 *       {@code X-New-Token} response header and a new session cookie.</li>
 * </ol>
 *
 * <p>Requests with no or invalid token proceed unauthenticated — route-level security
 * is enforced by {@link SecurityConfig#filterChain}.
 *
 * <p>The {@link fr.pivot.auth.entity.AccessToken} id resolved while validating the request is
 * also exposed as the {@value #CURRENT_TOKEN_ID_ATTRIBUTE} request attribute, so downstream
 * controllers that need to know "which session is this request using" (e.g. the active-sessions
 * self-service screen) can read it directly instead of re-validating the same bearer token a
 * second time against the database.
 */
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Request attribute carrying the {@link fr.pivot.auth.entity.AccessToken#getId()} of the
     * token that authenticated the current request, once this filter has run. Absent (never set)
     * when the request has no valid bearer token.
     */
    public static final String CURRENT_TOKEN_ID_ATTRIBUTE =
        TokenAuthenticationFilter.class.getName() + ".currentTokenId";

    private static final Logger LOG = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    private final TokenService tokenService;
    private final CookieHelper cookieHelper;

    /**
     * Constructs the filter with its collaborators.
     *
     * @param tokenService validates and rotates opaque session tokens
     * @param cookieHelper shared session-cookie helper
     */
    public TokenAuthenticationFilter(
            final TokenService tokenService,
            final CookieHelper cookieHelper) {
        this.tokenService = tokenService;
        this.cookieHelper = cookieHelper;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        final String rawToken = cookieHelper.extractBearerToken(request);

        if (rawToken != null) {
            tokenService.validate(rawToken).ifPresent(token -> {
                authenticateRequest(token.getUser());
                request.setAttribute(CURRENT_TOKEN_ID_ATTRIBUTE, token.getId());

                // Inline rotation when remaining TTL < admin-configured threshold.
                // rotate() returns empty if the token was already revoked by a concurrent
                // request — silent no-op, authentication already established above.
                final double threshold = tokenService.getRefreshThreshold();
                if (token.needsRefresh(threshold)) {
                    tokenService.rotate(token).ifPresentOrElse(newToken -> {
                        response.setHeader("X-New-Token", newToken.rawToken());
                        response.setHeader("X-Token-Expires-At",
                            String.valueOf(newToken.expiresAt().toEpochMilli()));
                        cookieHelper.setSessionCookie(response, newToken.rawToken(), newToken.ttlSeconds());
                        LOG.info("event=TOKEN_AUTO_REFRESHED userId={}",
                            token.getUser() != null ? token.getUser().getId() : "?");
                    }, () -> LOG.debug("event=TOKEN_ROTATE_NOOP reason=concurrent_revoke"));
                }
            });
        }

        chain.doFilter(request, response);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private void authenticateRequest(final User user) {
        final var auth = new UsernamePasswordAuthenticationToken(
            user.getEmail(),
            null,
            List.of(new SimpleGrantedAuthority(user.getRole()))
        );
        auth.setDetails(user);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
