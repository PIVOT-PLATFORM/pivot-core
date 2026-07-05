package fr.pivot.auth.controller;

import fr.pivot.auth.dto.SessionDto;
import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.SessionService;
import fr.pivot.auth.service.TokenService;
import fr.pivot.config.CookieHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST controller for the "active sessions" self-service screen (US02.2.3).
 *
 * <p>A thin HTTP layer over {@link SessionService} — all ownership checks, current-session
 * protection and mapping to {@link SessionDto} are delegated there. {@code userId} is always
 * resolved from the {@link User} in the authenticated request's details (populated by
 * {@link fr.pivot.config.TokenAuthenticationFilter}), never from a path/query/body value.
 *
 * <p>The current session's {@link fr.pivot.auth.entity.AccessToken} id is not carried by the
 * {@link Authentication} populated by the filter (only the resolved {@link User} is), so it is
 * re-resolved here from the same {@code Authorization: Bearer} header via
 * {@link TokenService#validate(String)} — an extra DB read, on this low-traffic screen only.
 */
@RestController
@RequestMapping("/api/account/sessions")
public class SessionController {

    private static final Logger LOG = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessionService;
    private final TokenService tokenService;
    private final CookieHelper cookieHelper;

    /**
     * Constructs the controller with its required service collaborators.
     *
     * @param sessionService manages listing and revocation of active sessions
     * @param tokenService   resolves the current request's {@link fr.pivot.auth.entity.AccessToken}
     * @param cookieHelper   shared Bearer-token extraction helper
     */
    public SessionController(
            final SessionService sessionService,
            final TokenService tokenService,
            final CookieHelper cookieHelper) {
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.cookieHelper = cookieHelper;
    }

    /**
     * Lists the current user's active sessions, most recently created first.
     *
     * @param http incoming request (used to resolve the current session's token id)
     * @return 200 with the list of {@link SessionDto}
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SessionDto>> listSessions(final HttpServletRequest http) {
        final User user = currentUser();
        final Long currentTokenId = resolveCurrentTokenId(http);
        LOG.info("event=LIST_SESSIONS userId={}", user.getId());
        return ResponseEntity.ok(sessionService.listSessions(user, currentTokenId));
    }

    /**
     * Revokes a single session belonging to the current user.
     *
     * @param tokenId id of the {@link fr.pivot.auth.entity.AccessToken} to revoke
     * @param http    incoming request (used to resolve the current session's token id)
     * @return 204 No Content on success
     * @throws ResponseStatusException 404 if the token does not belong to the current user,
     *     403 if {@code tokenId} is the current session, 401 if the current session cannot
     *     be resolved
     */
    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void revokeSession(@PathVariable final Long tokenId, final HttpServletRequest http) {
        final User user = currentUser();
        final Long currentTokenId = requireCurrentTokenId(http);
        sessionService.revokeSession(user, tokenId, currentTokenId);
    }

    /**
     * Revokes every active session of the current user except the current one.
     *
     * @param http incoming request (used to resolve the current session's token id)
     * @return 204 No Content on success
     * @throws ResponseStatusException 401 if the current session cannot be resolved
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("isAuthenticated()")
    public void revokeAllExceptCurrent(final HttpServletRequest http) {
        final User user = currentUser();
        final Long currentTokenId = requireCurrentTokenId(http);
        sessionService.revokeAllSessionsExceptCurrent(user, currentTokenId);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private User currentUser() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof User user)) {
            LOG.warn("event=SESSIONS_REJECTED reason=invalid_auth_details");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    /**
     * Resolves the {@link fr.pivot.auth.entity.AccessToken} id backing the current request,
     * or {@code null} if it cannot be resolved (never fails the request — used on the read path
     * where a missing {@code isCurrent} flag is preferable to a hard error).
     */
    private Long resolveCurrentTokenId(final HttpServletRequest http) {
        final String rawToken = cookieHelper.extractBearerToken(http);
        return tokenService.validate(rawToken).map(AccessToken::getId).orElse(null);
    }

    /**
     * Same as {@link #resolveCurrentTokenId} but fails the request with 401 if the current
     * session cannot be resolved — required on write paths, where silently treating "unknown
     * current session" as "no current session" would let a caller revoke its own current token.
     */
    private Long requireCurrentTokenId(final HttpServletRequest http) {
        final Long currentTokenId = resolveCurrentTokenId(http);
        if (currentTokenId == null) {
            LOG.warn("event=SESSIONS_REJECTED reason=current_token_unresolved");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return currentTokenId;
    }
}
