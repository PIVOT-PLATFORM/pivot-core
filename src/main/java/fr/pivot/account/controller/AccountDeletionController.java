package fr.pivot.account.controller;

import fr.pivot.account.dto.AccountDeletionCancelRequest;
import fr.pivot.account.dto.AccountDeletionRequestDto;
import fr.pivot.account.dto.AccountDeletionResponseDto;
import fr.pivot.account.service.AccountDeletionService;
import fr.pivot.auth.entity.User;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for the account-deletion flow (US02.2.4, RGPD Art. 17).
 *
 * <p>Handles HTTP concerns only — identity extraction from the security context, IP/user-agent
 * extraction. All business logic is delegated to {@link AccountDeletionService}.
 *
 * <p>{@link #deleteAccount}, {@link #requestOtp} and {@link #confirmationMethod} require
 * authentication; identity is resolved exclusively from the {@link User} entity stashed by
 * {@code TokenAuthenticationFilter} into the current {@link Authentication#getDetails()} — never
 * from the request body, per the {@code /api/account/*} hard rule (no endpoint here accepts a
 * {@code userId}/{@code accountId} field). {@link #cancel}, in contrast, is reached from an
 * emailed link and is deliberately unauthenticated (see {@code SecurityConfig}) — every session
 * was revoked the moment the deletion was requested, so identity there comes solely from the
 * single-use cancellation token.
 */
@RestController("accountDeletionController")
@RequestMapping("/account")
public class AccountDeletionController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionController.class);
    private static final String HEADER_USER_AGENT = "User-Agent";

    private final AccountDeletionService accountDeletionService;
    private final CookieHelper cookieHelper;

    /**
     * Constructs the controller with its required collaborators.
     *
     * @param accountDeletionService manages the account-deletion request/cancel/purge flow
     * @param cookieHelper           shared client-IP resolution helper
     */
    public AccountDeletionController(
            final AccountDeletionService accountDeletionService,
            final CookieHelper cookieHelper) {
        this.accountDeletionService = accountDeletionService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Tells the frontend which confirmation step to render before the user reaches the final
     * "delete my account" dialog step.
     *
     * @return {@code 200} with {@code {"method": "PASSWORD"|"OTP"}} · {@code 401} if the
     *     authentication context is invalid
     */
    @GetMapping("/deletion/confirmation-method")
    public ResponseEntity<Map<String, String>> confirmationMethod() {
        final User user = resolveUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of("method", accountDeletionService.confirmationMethod(user).name()));
    }

    /**
     * Emails a 6-digit confirmation OTP — accounts with no local password only (auth_mode
     * OIDC / Google-only). The frontend calls this before showing the OTP input field, then
     * submits {@code DELETE /account} with the code.
     *
     * @param request incoming request (IP, User-Agent extraction)
     * @return {@code 202} Accepted · {@code 400} if the account has a local password ·
     *     {@code 401} if the authentication context is invalid · {@code 409} if a deletion is
     *     already pending · {@code 429} if rate-limited
     */
    @PostMapping("/deletion/otp")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestOtp(final HttpServletRequest request) {
        final User user = resolveUserOrThrow();
        accountDeletionService.requestOtp(user, cookieHelper.clientIp(request), request.getHeader(HEADER_USER_AGENT));
    }

    /**
     * Confirms and immediately applies the deletion of the authenticated user's account.
     *
     * <p>On success: every session is revoked (including the one that authenticated this very
     * request — the response is still returned, but any subsequent request needs a fresh login),
     * the account moves to PENDING_DELETION, and a confirmation email with the effective purge
     * date and cancellation link is sent.
     *
     * @param req     current-password XOR OTP confirmation, depending on {@link
     *                #confirmationMethod}
     * @param request incoming request (IP, User-Agent extraction)
     * @return {@code 200} with {@link AccountDeletionResponseDto} · {@code 401} if the
     *     authentication context is invalid · {@code 403} if the confirmation is missing or
     *     invalid · {@code 409} if a deletion is already pending
     */
    @DeleteMapping
    public ResponseEntity<AccountDeletionResponseDto> deleteAccount(
            @RequestBody(required = false) final AccountDeletionRequestDto req,
            final HttpServletRequest request) {
        final User user = resolveUser();
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        final AccountDeletionRequestDto body = req != null ? req : new AccountDeletionRequestDto(null, null);
        final Instant effectiveAt = accountDeletionService.requestDeletion(
            user, body, cookieHelper.clientIp(request), request.getHeader(HEADER_USER_AGENT));
        LOG.info("event=ACCOUNT_DELETION_REQUESTED_HTTP userId={}", user.getId());
        return ResponseEntity.ok(new AccountDeletionResponseDto(effectiveAt));
    }

    /**
     * Cancels a pending deletion from the token embedded in the confirmation email's
     * cancellation link. Public endpoint (see {@code SecurityConfig}).
     *
     * @param req     the raw cancellation token
     * @param request incoming request (IP, User-Agent extraction)
     * @return {@code 200} on success · {@code 400} if the token is invalid/already used ·
     *     {@code 410} if the account was already anonymized (too late to cancel) ·
     *     {@code 429} if rate-limited
     */
    @PostMapping("/deletion/cancel")
    public ResponseEntity<Map<String, String>> cancel(
            @Valid @RequestBody final AccountDeletionCancelRequest req,
            final HttpServletRequest request) {
        accountDeletionService.cancelDeletion(
            req.token(), cookieHelper.clientIp(request), request.getHeader(HEADER_USER_AGENT));
        return ResponseEntity.ok(Map.of("message", "Suppression annulée. Votre compte est de nouveau actif."));
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Resolves the authenticated user from the security context.
     *
     * @return the authenticated {@link User}, or {@code null} if the authentication context is
     *     invalid (no details, or details not a {@link User})
     */
    private User resolveUser() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=ACCOUNT_DELETION_REJECTED reason=invalid_auth_details");
            return null;
        }
        return user;
    }

    /**
     * Same as {@link #resolveUser()}, throwing 401 instead of returning {@code null} — used by
     * the {@code void}-returning {@link #requestOtp} endpoint, which has no response body to
     * shape a manual 401 into.
     *
     * @return the authenticated {@link User}
     * @throws ResponseStatusException 401 if the authentication context is invalid
     */
    private User resolveUserOrThrow() {
        final User user = resolveUser();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }
}
