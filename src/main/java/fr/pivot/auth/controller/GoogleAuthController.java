package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.GoogleAuthRequest;
import fr.pivot.auth.service.GoogleAuthService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
    private final CookieHelper cookieHelper;

    /**
     * Constructs the controller with its required collaborators.
     *
     * @param googleAuthService Google ID token verifier and session issuer
     * @param cookieHelper      shared session-cookie + client-IP helper
     */
    public GoogleAuthController(
            final GoogleAuthService googleAuthService,
            final CookieHelper cookieHelper) {
        this.googleAuthService = googleAuthService;
        this.cookieHelper = cookieHelper;
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
            googleAuthService.authenticate(req, cookieHelper.clientIp(http), http.getHeader("User-Agent"));

        cookieHelper.setSessionCookie(res, result.sessionToken(), result.ttlSeconds());
        return ResponseEntity.ok(new AuthResponse(result.sessionToken(), result.expiresAt(), result.user()));
    }
}
