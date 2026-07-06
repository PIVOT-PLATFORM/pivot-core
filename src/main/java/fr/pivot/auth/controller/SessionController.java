package fr.pivot.auth.controller;

import fr.pivot.auth.dto.SessionDto;
import fr.pivot.auth.entity.User;
import fr.pivot.auth.service.SessionService;
import fr.pivot.config.CookieHelper;
import fr.pivot.config.CurrentSessionResolver;
import fr.pivot.config.TokenAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
 * {@link Authentication} populated by the filter (only the resolved {@link User} is). Rather than
 * re-validating the same bearer token a second time against the database, it is read from the
 * {@value TokenAuthenticationFilter#CURRENT_TOKEN_ID_ATTRIBUTE} request attribute that
 * {@link TokenAuthenticationFilter} already populates while authenticating the request — via the
 * shared {@link CurrentSessionResolver} (US01.4.2 — also used by {@code DeviceController}, which
 * needs the exact same "who is calling" / "which session" resolution).
 */
@RestController
@RequestMapping("/api/account/sessions")
public class SessionController {

    private static final Logger LOG = LoggerFactory.getLogger(SessionController.class);

    /** Structured-log event name used by {@link CurrentSessionResolver} on rejection. */
    private static final String REJECTED_EVENT = "SESSIONS_REJECTED";

    private final SessionService sessionService;
    private final CurrentSessionResolver sessionResolver;
    private final CookieHelper cookieHelper;

    /**
     * Constructs the controller with its required collaborators.
     *
     * @param sessionService  manages listing and revocation of active sessions
     * @param sessionResolver resolves the authenticated user and current token id from a request
     * @param cookieHelper    resolves the trusted client IP for the revocation security
     *                        notification (US01.5.1)
     */
    public SessionController(final SessionService sessionService, final CurrentSessionResolver sessionResolver,
                              final CookieHelper cookieHelper) {
        this.sessionService = sessionService;
        this.sessionResolver = sessionResolver;
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
        final User user = sessionResolver.currentUser(LOG, REJECTED_EVENT);
        final Long currentTokenId = sessionResolver.currentTokenId(http).orElse(null);
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
        final User user = sessionResolver.currentUser(LOG, REJECTED_EVENT);
        final Long currentTokenId = sessionResolver.requireCurrentTokenId(http, LOG, REJECTED_EVENT);
        sessionService.revokeSession(user, tokenId, currentTokenId, cookieHelper.clientIp(http));
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
        final User user = sessionResolver.currentUser(LOG, REJECTED_EVENT);
        final Long currentTokenId = sessionResolver.requireCurrentTokenId(http, LOG, REJECTED_EVENT);
        sessionService.revokeAllSessionsExceptCurrent(user, currentTokenId, cookieHelper.clientIp(http));
    }
}
