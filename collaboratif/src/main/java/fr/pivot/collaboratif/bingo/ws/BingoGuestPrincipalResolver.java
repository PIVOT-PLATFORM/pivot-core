package fr.pivot.collaboratif.bingo.ws;

import fr.pivot.collaboratif.whiteboard.ws.GuestPrincipalResolver;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves an anonymous Bingo participant's {@code X-Guest-Token} CONNECT credential (US47.1.1,
 * AC-47.1.1-03) — one of possibly several {@link GuestPrincipalResolver} implementations tried in
 * turn by {@code StompAuthenticationChannelInterceptor} (this module's shared client inbound
 * channel authenticates every CONNECT frame regardless of which STOMP endpoint/domain it targets).
 *
 * <p><strong>Token shape.</strong> Unlike Module Session's plain opaque {@code guestToken}
 * (looked up directly against a persisted {@code Participant} row), Bingo's access grant is keyed
 * by the pair {@code (roomId, accessToken)} (SEC-01) — there is no single-argument lookup that can
 * resolve an accessToken alone. The frontend therefore composes the CONNECT-time credential as
 * {@code "{roomId}:{accessToken}"}; this resolver parses that exact shape and delegates the real
 * validity check to {@link BingoRoomAccessGrantService#hasAccess}, the same check {@link
 * BingoChannelInterceptor} performs on every subsequent SUBSCRIBE/SEND. A malformed value, or a
 * value that does not resolve to an active grant, returns empty (falls through to the next
 * resolver, or ultimately rejects the CONNECT) — this class never accepts an unverified token.
 *
 * <p>The principal this resolver establishes carries no authorization weight of its own (see
 * {@link BingoGuestPrincipal}'s Javadoc) — it exists solely so the CONNECT frame succeeds and
 * {@code /user/queue/errors} can be addressed to the session.
 */
@Component
public class BingoGuestPrincipalResolver implements GuestPrincipalResolver {

    private static final char SEPARATOR = ':';

    private final BingoRoomAccessGrantService roomAccessGrantService;

    /**
     * Creates the resolver with its required dependency.
     *
     * @param roomAccessGrantService grant store used to validate the parsed {@code (roomId,
     *                               accessToken)} pair
     */
    public BingoGuestPrincipalResolver(final BingoRoomAccessGrantService roomAccessGrantService) {
        this.roomAccessGrantService = roomAccessGrantService;
    }

    /**
     * Resolves a {@code "{roomId}:{accessToken}"} guest token.
     *
     * @param guestToken the raw native {@code X-Guest-Token} header value
     * @return an anonymous {@link BingoGuestPrincipal}, or empty if the value is not this
     *     resolver's shape or does not resolve to a currently valid grant
     */
    @Override
    public Optional<Principal> resolveGuest(final String guestToken) {
        if (guestToken == null) {
            return Optional.empty();
        }
        int separator = guestToken.indexOf(SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        UUID roomId = parseUuid(guestToken.substring(0, separator));
        String accessToken = guestToken.substring(separator + 1);
        if (roomId == null || accessToken.isBlank()) {
            return Optional.empty();
        }
        if (!roomAccessGrantService.hasAccess(roomId, accessToken)) {
            return Optional.empty();
        }
        return Optional.of(new BingoGuestPrincipal(UUID.randomUUID()));
    }

    private static UUID parseUuid(final String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
