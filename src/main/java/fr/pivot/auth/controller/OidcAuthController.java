package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.OidcExchangeRequest;
import fr.pivot.auth.service.OidcAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for enterprise OIDC (OpenID Connect) authentication.
 *
 * <p>Provides two endpoints:
 * <ul>
 *   <li>GET /auth/oidc/config — Angular reads tenant OIDC provider details before PKCE flow</li>
 *   <li>POST /auth/oidc/exchange — Angular sends the enterprise access token for pivot session issuance</li>
 * </ul>
 *
 * <p>Cookie TTL comes from {@link OidcAuthService.OidcLoginResult#ttlSeconds()}
 * which is populated by {@link fr.pivot.auth.service.TokenService} from the
 * {@code SESSION_TTL_SECONDS} feature flag.
 */
@RestController
@RequestMapping("/auth/oidc")
public class OidcAuthController {

    private final OidcAuthService oidcAuthService;
    private final String sessionCookieName;
    private final boolean secureCookie;

    /**
     * Constructs the controller with its required collaborators.
     *
     * @param oidcAuthService   enterprise OIDC token exchange service
     * @param sessionCookieName name of the HTTP-only session persistence cookie
     * @param secureCookie      whether to set the {@code Secure} flag on the session cookie
     */
    public OidcAuthController(
            final OidcAuthService oidcAuthService,
            @Value("${pivot.auth.session-cookie-name:pivot_session}") final String sessionCookieName,
            @Value("${pivot.auth.secure-cookie:true}") final boolean secureCookie) {
        this.oidcAuthService = oidcAuthService;
        this.sessionCookieName = sessionCookieName;
        this.secureCookie = secureCookie;
    }

    /**
     * Returns the OIDC provider configuration for a tenant.
     *
     * <p>Angular fetches this before starting the PKCE flow to discover
     * the IdP issuer URI, client ID and requested scopes.
     *
     * @param tenantSlug the tenant's URL slug (e.g. "acme-corp")
     * @return OIDC client config for the given tenant
     */
    @GetMapping("/config")
    public OidcAuthService.OidcClientConfig getConfig(@RequestParam final String tenantSlug) {
        return oidcAuthService.getClientConfig(tenantSlug);
    }

    /**
     * Exchanges an enterprise IdP access token for a pivot opaque session token.
     *
     * @param req  OIDC exchange payload (tenant slug, access token, optional device info)
     * @param http incoming request (IP, User-Agent extraction)
     * @param res  outgoing response (session cookie)
     * @return 200 with {@link AuthResponse} containing the opaque session token
     */
    @PostMapping("/exchange")
    public ResponseEntity<AuthResponse> exchange(@Valid @RequestBody final OidcExchangeRequest req,
                                                  final HttpServletRequest http,
                                                  final HttpServletResponse res) {
        final OidcAuthService.OidcLoginResult result =
            oidcAuthService.exchange(req, getIp(http), http.getHeader("User-Agent"));

        setSessionCookie(res, result.sessionToken(), result.ttlSeconds());
        return ResponseEntity.ok(new AuthResponse(result.sessionToken(), result.expiresAt(), result.user()));
    }

    // ----------------------------------------------------------------
    // HTTP-level helpers (cookie + IP) — no business logic
    // ----------------------------------------------------------------

    private void setSessionCookie(final HttpServletResponse res, final String rawToken, final int ttlSeconds) {
        final Cookie cookie = new Cookie(sessionCookieName, rawToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath("/api/auth");
        cookie.setMaxAge(ttlSeconds);
        cookie.setAttribute("SameSite", "Strict");
        res.addCookie(cookie);
    }

    private String getIp(final HttpServletRequest req) {
        final String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
