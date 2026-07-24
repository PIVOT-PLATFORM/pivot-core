package fr.pivot.collaboratif.session.ws;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP principal for an anonymous {@code ROLE_GUEST} Module Session participant (US19.2.1) —
 * carries no {@code userId}/{@code tenantId}, unlike {@link
 * fr.pivot.collaboratif.whiteboard.ws.StompPrincipal}, since guests are scoped strictly to the
 * single session they joined.
 *
 * @param sessionId     the session this guest joined
 * @param participantId the guest's {@code Participant} row id
 */
public record SessionGuestPrincipal(UUID sessionId, UUID participantId) implements Principal {

    @Override
    public String getName() {
        return "guest:" + participantId;
    }
}
