package fr.pivot.collaboratif.whiteboard.ws;

import java.security.Principal;
import java.util.Optional;

/**
 * Resolves a guest-scoped credential (e.g. a Module Session {@code guestToken}) presented on a
 * STOMP {@code CONNECT} frame into a {@link Principal}, for callers who never obtained a bearer
 * token (US19.2.1 anonymous participation).
 *
 * <p>Kept as a small seam in this shared {@code whiteboard.ws} package — rather than importing a
 * domain-specific repository directly into {@link StompAuthenticationChannelInterceptor} — so
 * that adding a new guest-credential type never requires touching this already-tested,
 * security-critical interceptor beyond its single injection point.
 */
public interface GuestPrincipalResolver {

    /**
     * Attempts to resolve a raw guest token into a principal.
     *
     * @param guestToken the raw token presented in the {@code X-Guest-Token} native STOMP header
     * @return the resolved principal, or empty if the token is unknown/expired
     */
    Optional<Principal> resolveGuest(String guestToken);
}
