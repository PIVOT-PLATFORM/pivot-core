package fr.pivot.collaboratif.session.ws;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionGuestPrincipal} (US19.2.1) — mainly {@link
 * SessionGuestPrincipal#getName()}, which is what ends up on STOMP frames'
 * {@code accessor.getUser().getName()} and drives {@code /user/queue/errors} routing in {@link
 * SessionChannelInterceptor#sendError}.
 */
class SessionGuestPrincipalTest {

    @Test
    void getNameIsPrefixedByGuestAndCarriesTheParticipantId() {
        UUID participantId = UUID.randomUUID();
        SessionGuestPrincipal principal = new SessionGuestPrincipal(UUID.randomUUID(), participantId);

        assertThat(principal.getName()).isEqualTo("guest:" + participantId);
    }

    @Test
    void accessorsExposeTheConstructorArguments() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();

        SessionGuestPrincipal principal = new SessionGuestPrincipal(sessionId, participantId);

        assertThat(principal.sessionId()).isEqualTo(sessionId);
        assertThat(principal.participantId()).isEqualTo(participantId);
    }
}
