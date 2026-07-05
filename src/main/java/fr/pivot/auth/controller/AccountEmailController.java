package fr.pivot.auth.controller;

import fr.pivot.auth.dto.ChangeEmailRequest;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.EmailChangeService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the authenticated "change my email" endpoints (US02.2.2).
 *
 * <p>Handles HTTP concerns only — identity extraction from the security context, IP/user-agent
 * extraction. All business logic is delegated to {@link EmailChangeService}.
 *
 * <p>{@link #requestChange} requires authentication; identity is resolved exclusively from the
 * {@link User} entity stashed by {@code TokenAuthenticationFilter} into the current
 * {@link Authentication#getDetails()} — never from the request body, so {@link ChangeEmailRequest}
 * carries no {@code userId} / {@code accountId} field. {@link #confirm}, in contrast, is reached
 * from an emailed link and therefore deliberately unauthenticated (see {@code SecurityConfig})
 * — identity there comes solely from the single-use token.
 */
@RestController
@RequestMapping("/account/email")
public class AccountEmailController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountEmailController.class);
    private static final String HEADER_USER_AGENT = "User-Agent";

    private final EmailChangeService emailChangeService;
    private final CookieHelper cookieHelper;

    /**
     * Constructs the controller with its required collaborators.
     *
     * @param emailChangeService manages the email-change request/confirm flow
     * @param cookieHelper       shared client-IP helper
     */
    public AccountEmailController(
            final EmailChangeService emailChangeService,
            final CookieHelper cookieHelper) {
        this.emailChangeService = emailChangeService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Requests a change of the authenticated user's email address.
     *
     * <p>Always returns 202 Accepted — whether or not {@code newEmail} already belongs to
     * another account is never observable from this response (anti-enumeration); a duplicate
     * is instead reported by email to the candidate address only.
     *
     * @param req  new-email + current-password payload — carries no {@code userId} /
     *             {@code accountId} field to smuggle in the first place; any extra JSON field
     *             (e.g. a spoofed {@code userId}) is simply not bound to anything and is ignored
     *             by Jackson in this codebase's current configuration (no global
     *             {@code fail-on-unknown-properties}) — identity always comes from the bearer
     *             token below, never from the body
     * @param http incoming request (IP, User-Agent extraction)
     * @return 202 Accepted on every outcome except 401 (wrong password) and 429 (rate limit)
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> requestChange(
            @Valid @RequestBody final ChangeEmailRequest req,
            final HttpServletRequest http) {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth.getDetails() instanceof User currentUser)) {
            LOG.warn("event=EMAIL_CHANGE_REJECTED reason=invalid_auth_details");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        emailChangeService.requestEmailChange(
            currentUser.getId(), req, cookieHelper.clientIp(http), http.getHeader(HEADER_USER_AGENT));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /**
     * Confirms a pending email change from the token embedded in the confirmation link.
     *
     * <p>Public endpoint (see {@code SecurityConfig}) — the link may be opened on a device with
     * no active PIVOT session; identity comes solely from the token.
     *
     * @param token raw confirmation token from the {@code ?token=} query parameter
     * @param http  incoming request (IP, User-Agent extraction)
     * @return 200 on success; the global exception handler maps token failures to
     *     400 (invalid/expired), 410 (already used) or 409 (target address taken meanwhile)
     */
    @GetMapping("/confirm")
    public ResponseEntity<Void> confirm(
            @RequestParam final String token,
            final HttpServletRequest http) {
        emailChangeService.confirmEmailChange(
            token, cookieHelper.clientIp(http), http.getHeader(HEADER_USER_AGENT));
        return ResponseEntity.ok().build();
    }
}
