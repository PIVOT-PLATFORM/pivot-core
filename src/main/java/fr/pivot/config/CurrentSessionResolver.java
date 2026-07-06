package fr.pivot.config;

import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Resolves "who is calling" and "which session is this request using" from an
 * {@link HttpServletRequest} — shared by the two self-service screens that both need it:
 * {@code SessionController} (US02.2.3) and {@code DeviceController} (US01.4.2).
 *
 * <p>Replaces the {@code currentUser()}/{@code resolveCurrentTokenId()}/
 * {@code requireCurrentTokenId()} methods that were duplicated verbatim across those two
 * controllers, following the same DRY precedent as {@link CookieHelper} (which centralised
 * cookie/bearer-token helpers previously copied across {@code AuthController},
 * {@code OidcAuthController} and {@code GoogleAuthController}).
 *
 * <p>The throwing convenience methods ({@link #currentUser(Logger, String)},
 * {@link #requireCurrentTokenId(HttpServletRequest, Logger, String)}) take the caller's own
 * {@link Logger} and structured-log event name (e.g. {@code SESSIONS_REJECTED} vs
 * {@code DEVICES_REJECTED}) as parameters, so each controller keeps its own distinguishable log
 * output — composition over a shared base class, consistent with this codebase not sharing
 * controller behaviour via inheritance.
 */
@Component
public class CurrentSessionResolver {

    /**
     * Resolves the authenticated {@link User} from the security context populated by
     * {@link TokenAuthenticationFilter}.
     *
     * @return the authenticated user, or empty if the request has no valid authentication
     */
    public Optional<User> currentUser() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof User user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /**
     * Same as {@link #currentUser()} but fails with 401 and logs {@code rejectedEvent} when the
     * request has no valid authentication.
     *
     * @param log           the calling controller's logger
     * @param rejectedEvent structured-log event name to use on rejection (e.g.
     *                      {@code SESSIONS_REJECTED})
     * @return the authenticated user
     * @throws ResponseStatusException 401 if the request has no valid authentication
     */
    public User currentUser(final Logger log, final String rejectedEvent) {
        return currentUser()
            .orElseThrow(() -> {
                log.warn("event={} reason=invalid_auth_details", rejectedEvent);
                return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            });
    }

    /**
     * Resolves the {@link AccessToken} id backing the current request from the
     * {@value TokenAuthenticationFilter#CURRENT_TOKEN_ID_ATTRIBUTE} request attribute that
     * {@link TokenAuthenticationFilter} populates while authenticating the request — avoids
     * re-validating the same bearer token a second time against the database.
     *
     * @param http incoming request
     * @return the current session's token id, or empty if it could not be resolved
     */
    public Optional<Long> currentTokenId(final HttpServletRequest http) {
        final Object attribute = http.getAttribute(TokenAuthenticationFilter.CURRENT_TOKEN_ID_ATTRIBUTE);
        return attribute instanceof Long tokenId ? Optional.of(tokenId) : Optional.empty();
    }

    /**
     * Same as {@link #currentTokenId(HttpServletRequest)} but fails with 401 and logs
     * {@code rejectedEvent} when the current session cannot be resolved — required on write
     * paths, where silently treating "unknown current session" as "no current session" would
     * let a caller bypass a current-resource guard.
     *
     * @param http          incoming request
     * @param log           the calling controller's logger
     * @param rejectedEvent structured-log event name to use on rejection (e.g.
     *                      {@code DEVICES_REJECTED})
     * @return the current session's token id
     * @throws ResponseStatusException 401 if the current session cannot be resolved
     */
    public Long requireCurrentTokenId(final HttpServletRequest http, final Logger log, final String rejectedEvent) {
        return currentTokenId(http)
            .orElseThrow(() -> {
                log.warn("event={} reason=current_token_unresolved", rejectedEvent);
                return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            });
    }
}
