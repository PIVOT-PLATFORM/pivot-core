package fr.pivot.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Centralises HTTP-level concerns shared by the auth controllers and the token filter:
 * session-cookie issuance/clearing and trusted client-IP extraction.
 *
 * <p>Replaces the {@code setSessionCookie}/{@code clearSessionCookie}/{@code getIp} methods
 * that were copied verbatim across {@code AuthController}, {@code OidcAuthController},
 * {@code GoogleAuthController} and {@code TokenAuthenticationFilter} (DRY), guaranteeing a
 * single source of truth for the {@code HttpOnly}/{@code Secure}/{@code SameSite}/path
 * cookie attributes. Also centralises {@code Authorization: Bearer} header extraction, shared
 * by {@link fr.pivot.config.TokenAuthenticationFilter} and controllers that need to resolve
 * the current request's raw token (e.g. {@code SessionController}, US02.2.3).
 */
@Component
public class CookieHelper {

    private static final String COOKIE_PATH = "/api/auth";
    private static final String SAME_SITE = "Strict";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String sessionCookieName;
    private final boolean secureCookie;

    /**
     * @param sessionCookieName name of the HTTP-only session persistence cookie
     * @param secureCookie      whether to set the {@code Secure} flag (true in prod / HTTPS)
     */
    public CookieHelper(
            @Value("${pivot.auth.session-cookie-name:pivot_session}") final String sessionCookieName,
            @Value("${pivot.auth.secure-cookie:true}") final boolean secureCookie) {
        this.sessionCookieName = sessionCookieName;
        this.secureCookie = secureCookie;
    }

    /**
     * Writes the session cookie carrying the opaque token.
     *
     * @param res        outgoing response
     * @param rawToken   raw opaque session token
     * @param ttlSeconds cookie {@code Max-Age} in seconds
     */
    public void setSessionCookie(final HttpServletResponse res, final String rawToken, final int ttlSeconds) {
        final Cookie cookie = baseCookie(rawToken);
        cookie.setMaxAge(ttlSeconds);
        res.addCookie(cookie);
    }

    /**
     * Clears the session cookie (logout).
     *
     * @param res outgoing response
     */
    public void clearSessionCookie(final HttpServletResponse res) {
        final Cookie cookie = baseCookie("");
        cookie.setMaxAge(0);
        res.addCookie(cookie);
    }

    /**
     * Extracts the raw session token from the request cookies.
     *
     * @param req incoming request
     * @return the raw token, or {@code null} if the cookie is absent
     */
    public String extractSessionCookie(final HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        return Arrays.stream(req.getCookies())
            .filter(c -> sessionCookieName.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }

    /**
     * Returns the trusted client IP.
     *
     * <p>Relies on Tomcat's {@code RemoteIpValve} (enabled via {@code server.tomcat.remoteip.*}):
     * the {@code X-Forwarded-For} header is honoured <em>only</em> when the immediate peer is a
     * configured internal proxy, otherwise the real socket address is returned. This prevents a
     * directly-reachable attacker from spoofing XFF to bypass per-IP rate limiting or poison the
     * audit trail.
     *
     * @param req incoming request
     * @return the resolved client IP
     */
    public String clientIp(final HttpServletRequest req) {
        return req.getRemoteAddr();
    }

    /**
     * Extracts the raw opaque token from the {@code Authorization: Bearer <token>} header.
     *
     * @param req incoming request
     * @return the raw token, or {@code null} if the header is absent, malformed, or blank
     */
    public String extractBearerToken(final HttpServletRequest req) {
        final String header = req.getHeader(AUTHORIZATION_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            final String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isBlank() ? null : token;
        }
        return null;
    }

    private Cookie baseCookie(final String value) {
        final Cookie cookie = new Cookie(sessionCookieName, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath(COOKIE_PATH);
        cookie.setAttribute("SameSite", SAME_SITE);
        return cookie;
    }
}
