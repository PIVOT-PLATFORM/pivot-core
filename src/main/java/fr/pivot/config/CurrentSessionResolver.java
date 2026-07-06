package fr.pivot.config;

import fr.pivot.auth.entity.AccessToken;
import fr.pivot.auth.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Resolves "who is calling" and "which session is this request using" from an
 * {@link HttpServletRequest} — shared by the two self-service screens that both need it:
 * {@code SessionController} (US02.2.3) and {@code DeviceController} (US01.4.2).
 *
 * <p>Replaces the {@code currentUser()}/{@code resolveCurrentTokenId()} methods that were
 * duplicated verbatim across those two controllers, following the same DRY precedent as
 * {@link CookieHelper} (which centralised cookie/bearer-token helpers previously copied across
 * {@code AuthController}, {@code OidcAuthController} and {@code GoogleAuthController}).
 *
 * <p>Deliberately returns {@link Optional} rather than throwing or logging itself: each
 * controller keeps its own {@code ResponseStatusException} status code and structured log
 * event name (e.g. {@code SESSIONS_REJECTED} vs {@code DEVICES_REJECTED}) — composition over a
 * shared base class, consistent with this codebase not sharing controller behaviour via
 * inheritance.
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
}
