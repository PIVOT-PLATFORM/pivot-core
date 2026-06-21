package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.DeviceOtpRequest;
import fr.pivot.auth.dto.ForgotPasswordRequest;
import fr.pivot.auth.dto.LoginRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.dto.RegisterRequest;
import fr.pivot.auth.dto.ResetPasswordRequest;
import fr.pivot.auth.service.PasswordService;
import fr.pivot.auth.service.RegistrationService;
import fr.pivot.auth.service.SessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;

/**
 * REST controller for authentication endpoints.
 *
 * <p>Handles HTTP concerns only — cookie management, request extraction and
 * response building. All business logic is delegated to:
 * <ul>
 *   <li>{@link RegistrationService} — registration and email verification</li>
 *   <li>{@link SessionService} — login, session restore and logout</li>
 *   <li>{@link PasswordService} — forgot/reset password</li>
 * </ul>
 *
 * <p>From US-AUTH-002: cookie TTL is dynamic — it comes from {@link LoginResult#sessionTtlSeconds()}
 * (populated by {@link fr.pivot.auth.service.TokenService} from the admin-configurable
 * {@code SESSION_TTL_SECONDS} / {@code SESSION_TTL_REMEMBER_ME_SECONDS} feature flags).
 *
 * <p>All endpoints under {@code /auth/**} are public (no authentication required).
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String HEADER_USER_AGENT = "User-Agent";
    private static final String KEY_MESSAGE = "message";

    private final RegistrationService registrationService;
    private final SessionService sessionService;
    private final PasswordService passwordService;

    private final String sessionCookieName;
    private final boolean secureCookie;

    /**
     * Constructs the controller with its required service collaborators.
     *
     * @param registrationService manages account creation and email verification
     * @param sessionService      manages login, device OTP, session restore and logout
     * @param passwordService     manages forgot-password and reset-password flows
     * @param sessionCookieName   name of the HTTP-only session persistence cookie
     * @param secureCookie        whether to set the {@code Secure} flag on the session cookie
     */
    public AuthController(
            final RegistrationService registrationService,
            final SessionService sessionService,
            final PasswordService passwordService,
            @Value("${pivot.auth.session-cookie-name:pivot_session}") final String sessionCookieName,
            @Value("${pivot.auth.secure-cookie:true}") final boolean secureCookie) {
        this.registrationService = registrationService;
        this.sessionService = sessionService;
        this.passwordService = passwordService;
        this.sessionCookieName = sessionCookieName;
        this.secureCookie = secureCookie;
    }

    /**
     * Registers a new user account. Sends a verification email on success.
     *
     * @param req  registration payload
     * @param http incoming request (IP, User-Agent extraction)
     * @return 202 Accepted with a "check your inbox" message
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> register(@Valid @RequestBody final RegisterRequest req,
                                        final HttpServletRequest http) {
        registrationService.register(req, getIp(http), http.getHeader(HEADER_USER_AGENT));
        return Map.of(KEY_MESSAGE, "Vérifiez votre boîte email pour confirmer votre inscription.");
    }

    /**
     * Verifies an email address using the token sent by registration email.
     *
     * @param token the raw verification token (from query param)
     * @param http  incoming request (IP, User-Agent extraction)
     * @return 200 OK on success
     */
    @PostMapping("/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestParam final String token,
                                                           final HttpServletRequest http) {
        registrationService.verifyEmail(token, getIp(http), http.getHeader(HEADER_USER_AGENT));
        return ResponseEntity.ok(Map.of(KEY_MESSAGE, "Email vérifié avec succès."));
    }

    /**
     * Re-sends the verification email. Always returns 202 regardless of whether
     * the email was found (no enumeration).
     *
     * @param email the email address to resend verification to
     * @param http  incoming request
     * @return 202 Accepted
     */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> resendVerification(@RequestParam final String email,
                                                  final HttpServletRequest http) {
        registrationService.resendVerification(email, getIp(http), http.getHeader(HEADER_USER_AGENT));
        return Map.of(KEY_MESSAGE, "Si cet email est enregistré et non vérifié, un lien vous a été envoyé.");
    }

    /**
     * Authenticates by email and password. Issues a session token or triggers device MFA.
     *
     * <p>On device MFA: returns 202 with header {@code X-Device-Verification-Required: true}.
     * On success: sets the session cookie (for page reload persistence) and returns the
     * opaque token as {@code accessToken} (Angular stores it in memory only).
     *
     * @param req  login payload (email, password, optional device fingerprint/name, rememberMe)
     * @param http incoming request
     * @param res  outgoing response (session cookie)
     * @return 200 with {@link AuthResponse} on success, 202 if MFA required
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody final LoginRequest req,
                                              final HttpServletRequest http,
                                              final HttpServletResponse res) {
        final LoginResult result = sessionService.login(req, getIp(http), http.getHeader(HEADER_USER_AGENT));

        if (result.requiresDeviceVerification()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("X-Device-Verification-Required", "true")
                .build();
        }

        setSessionCookie(res, result.sessionToken(), (int) result.sessionTtlSeconds());
        return ResponseEntity.ok(new AuthResponse(result.sessionToken(), result.expiresAt(), result.user()));
    }

    /**
     * Validates the device OTP and completes the login flow.
     *
     * @param req  OTP payload (device fingerprint, 6-digit OTP, optional device name, rememberMe)
     * @param http incoming request
     * @param res  outgoing response (session cookie)
     * @return 200 with {@link AuthResponse}
     */
    @PostMapping("/device/verify")
    public ResponseEntity<AuthResponse> verifyDevice(@Valid @RequestBody final DeviceOtpRequest req,
                                                     final HttpServletRequest http,
                                                     final HttpServletResponse res) {
        final LoginResult result = sessionService.verifyDeviceOtp(req, getIp(http), http.getHeader(HEADER_USER_AGENT));
        setSessionCookie(res, result.sessionToken(), (int) result.sessionTtlSeconds());
        return ResponseEntity.ok(new AuthResponse(result.sessionToken(), result.expiresAt(), result.user()));
    }

    /**
     * Restores an existing session from the HTTP-only session cookie (page reload path).
     *
     * <p>Angular calls this on startup when the in-memory token is lost (page refresh).
     * The server validates the session cookie and returns the opaque token so Angular
     * can restore its in-memory state.
     *
     * <p>Token rotation (if threshold is crossed) happens on the next API request via
     * {@link fr.pivot.config.TokenAuthenticationFilter}.
     *
     * @param http incoming request (session cookie)
     * @param res  outgoing response
     * @return 200 with {@link AuthResponse}, or 401 if no valid session cookie
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(final HttpServletRequest http,
                                                final HttpServletResponse res) {
        final String rawCookieToken = extractSessionCookie(http);
        if (rawCookieToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        final LoginResult result = sessionService.restoreSession(
            rawCookieToken, getIp(http), http.getHeader(HEADER_USER_AGENT));
        return ResponseEntity.ok(new AuthResponse(result.sessionToken(), result.expiresAt(), result.user()));
    }

    /**
     * Revokes the session token and clears the session cookie.
     *
     * @param http incoming request (session cookie)
     * @param res  outgoing response (cleared cookie)
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(final HttpServletRequest http, final HttpServletResponse res) {
        sessionService.logout(extractSessionCookie(http));
        clearSessionCookie(res);
    }

    /**
     * Sends a password reset link to the given email. Always returns 202 (no enumeration).
     *
     * @param req  forgot-password payload (email address)
     * @param http incoming request
     * @return 202 Accepted
     */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> forgotPassword(@Valid @RequestBody final ForgotPasswordRequest req,
                                              final HttpServletRequest http) {
        passwordService.forgotPassword(req, getIp(http), http.getHeader(HEADER_USER_AGENT));
        return Map.of(KEY_MESSAGE, "Si cet email est enregistré, vous recevrez un lien de réinitialisation.");
    }

    /**
     * Resets the password using a valid reset token.
     *
     * @param req  reset-password payload (token + new password)
     * @param http incoming request
     * @return 200 OK on success
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody final ResetPasswordRequest req,
                                                             final HttpServletRequest http) {
        passwordService.resetPassword(req, getIp(http), http.getHeader(HEADER_USER_AGENT));
        return ResponseEntity.ok(Map.of(KEY_MESSAGE, "Mot de passe réinitialisé. Vous pouvez maintenant vous connecter."));
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

    private void clearSessionCookie(final HttpServletResponse res) {
        final Cookie cookie = new Cookie(sessionCookieName, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath("/api/auth");
        cookie.setMaxAge(0);
        res.addCookie(cookie);
    }

    private String extractSessionCookie(final HttpServletRequest req) {
        if (req.getCookies() == null) {
            return null;
        }
        return Arrays.stream(req.getCookies())
            .filter(c -> sessionCookieName.equals(c.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }

    private String getIp(final HttpServletRequest req) {
        final String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
