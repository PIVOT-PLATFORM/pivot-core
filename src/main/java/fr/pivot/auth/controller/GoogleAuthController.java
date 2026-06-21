package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.GoogleAuthRequest;
import fr.pivot.auth.service.GoogleAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Google Sign-In authentication.
 *
 * <p>Accepts the Google ID token obtained by Angular via the Google Sign-In SDK,
 * delegates verification to {@link GoogleAuthService}, and sets the session cookie.
 *
 * <p>Cookie TTL comes from {@link GoogleAuthService.GoogleLoginResult#ttlSeconds()}
 * which is populated by {@link fr.pivot.auth.service.TokenService} from the
 * {@code SESSION_TTL_SECONDS} feature flag.
 */
@RestController
@RequestMapping("/auth/google")
public class GoogleAuthController {

    private final GoogleAuthService googleAuthService;
    private final String sessionCookieName;
    private final boolean secureCookie;

    /**
     * Constructs the controller with its required collaborators.
     *
     * @param googleAuthService Google ID token verifier and session issuer
     * @param sessionCookieName name of the HTTP-only session persistence cookie
     * @param secureCookie      whether to set the {@code Secure} flag on the session cookie
     */
    public GoogleAuthController(
            final GoogleAuthService googleAuthService,
            @Value("${pivot.auth.session-cookie-name:pivot_session}") final String sessionCookieName,
            @Value("${pivot.auth.secure-cookie:true}") final boolean secureCookie) {
        this.googleAuthService = googleAuthService;
        this.sessionCookieName = sessionCookieName;
        this.secureCookie = secureCookie;
    }

    /**
     * Verifies the Google ID token and issues an opaque session token.
     *
     * @param req  Google auth payload (ID token, optional device fingerprint/name)
     * @param http incoming request (IP, User-Agent extraction)
     * @param res  outgoing response (session cookie)
     * @return 200 with {@link AuthResponse} containing the opaque session token
     */
    @PostMapping
    public ResponseEntity<AuthResponse> authenticate(@Valid @RequestBody final GoogleAuthRequest req,
                                                      final HttpServletRequest http,
                                                      final HttpServletResponse res) {
        final GoogleAuthService.GoogleLoginResult result =
            googleAuthService.authenticate(req, getIp(http), http.getHeader("User-Agent"));

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
