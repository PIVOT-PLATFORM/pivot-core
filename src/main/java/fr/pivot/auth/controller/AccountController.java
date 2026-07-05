package fr.pivot.auth.controller;

import fr.pivot.auth.dto.AuthResponse;
import fr.pivot.auth.dto.ChangePasswordRequest;
import fr.pivot.auth.dto.LoginResult;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.AccountPasswordService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the authenticated "my account" endpoints (US02.2.1).
 *
 * <p>Handles HTTP concerns only — identity extraction from the security context, cookie
 * management and response building. All business logic is delegated to
 * {@link AccountPasswordService}.
 *
 * <p>Every endpoint here requires authentication (no {@code permitAll} matcher for
 * {@code /account/**} in {@code SecurityConfig}); identity is resolved exclusively from the
 * {@link User} entity stashed by {@link fr.pivot.config.TokenAuthenticationFilter} into the
 * current {@link Authentication#getDetails()} — never from the request body, so no endpoint
 * here accepts a {@code userId} or {@code accountId} field.
 */
@RestController
@RequestMapping("/account")
public class AccountController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountController.class);
    private static final String HEADER_USER_AGENT = "User-Agent";

    private final AccountPasswordService accountPasswordService;
    private final CookieHelper cookieHelper;

    /**
     * Constructs the controller with its required collaborators.
     *
     * @param accountPasswordService manages the authenticated change-password flow
     * @param cookieHelper           shared session-cookie and client-IP helper
     */
    public AccountController(
            final AccountPasswordService accountPasswordService,
            final CookieHelper cookieHelper) {
        this.accountPasswordService = accountPasswordService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Changes the authenticated user's password.
     *
     * <p>On success, every previous session token is revoked and a brand-new one is issued for
     * the current device (see {@link AccountPasswordService} for the full rationale) — the new
     * session cookie is set and the new opaque token is returned as {@code accessToken} in the
     * response body, exactly like {@code /auth/login}.
     *
     * @param req  current + new password payload — rejects any unexpected JSON field (e.g. a
     *             spoofed {@code userId}) with 400 via Jackson's default unknown-property check
     * @param http incoming request (IP, User-Agent extraction)
     * @param res  outgoing response (new session cookie)
     * @return 200 with {@link AuthResponse} on success, 401 if the current password is wrong,
     *     429 if the rate limit is exceeded
     */
    @PostMapping("/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AuthResponse> changePassword(
            @Valid @RequestBody final ChangePasswordRequest req,
            final HttpServletRequest http,
            final HttpServletResponse res) {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth.getDetails() instanceof User currentUser)) {
            LOG.warn("event=CHANGE_PASSWORD_REJECTED reason=invalid_auth_details");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        final LoginResult result = accountPasswordService.changePassword(
            currentUser.getId(), req, cookieHelper.clientIp(http), http.getHeader(HEADER_USER_AGENT));

        cookieHelper.setSessionCookie(res, result.sessionToken(), (int) result.sessionTtlSeconds());
        return ResponseEntity.ok(new AuthResponse(result.sessionToken(), result.expiresAt(), result.user()));
    }
}
