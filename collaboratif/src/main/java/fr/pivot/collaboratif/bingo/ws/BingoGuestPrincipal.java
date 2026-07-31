package fr.pivot.collaboratif.bingo.ws;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP principal for an anonymous Bingo participant's {@code CONNECT} frame (US47.1.1,
 * AC-47.1.1-03) — carries no {@code userId}/{@code tenantId}, mirroring
 * {@code fr.pivot.collaboratif.session.ws.SessionGuestPrincipal}.
 *
 * <p>Established only so the CONNECT frame itself succeeds and {@code /user/queue/errors} can be
 * addressed to this session (via {@link #getName()}) — carries <strong>no authorization weight
 * whatsoever</strong>: every subsequent SUBSCRIBE/SEND on a Bingo destination is still
 * independently authorized by {@link BingoChannelInterceptor} against the {@code access-token}
 * native header, exactly like an authenticated participant's frames (SEC-01/SEC-02).
 *
 * @param sessionScope an opaque, per-CONNECT random identifier — never derived from the
 *                     accessToken itself, so this principal's {@link #getName()} leaks nothing
 */
public record BingoGuestPrincipal(UUID sessionScope) implements Principal {

    @Override
    public String getName() {
        return "bingo-guest:" + sessionScope;
    }
}
