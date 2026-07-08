package fr.pivot.notification.config;

import fr.pivot.core.auth.AuthenticatedPrincipal;
import fr.pivot.core.auth.AuthenticatedPrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour {@link StompAuthChannelInterceptor} (EN-NOTIF).
 *
 * <p>Vérifie l'authentification de la frame STOMP {@code CONNECT} par token opaque, seul point
 * d'authentification réel du canal WebSocket (la poignée de main HTTP est {@code permitAll()} —
 * voir {@link NotificationWebSocketConfig}).
 *
 * <p>Dépend de {@link AuthenticatedPrincipalResolver} (ADR-022) depuis EN17.1 volet {@code
 * fr.pivot.core.auth} — mock direct de l'interface plutôt que de {@code TokenService}/{@code
 * User} concrets, comportement de l'intercepteur inchangé.
 */
@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    private AuthenticatedPrincipalResolver principalResolver;

    @Mock
    private MessageChannel channel;

    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(principalResolver);
    }

    @Test
    void preSend_setsPrincipal_whenConnectFrameHasValidBearerToken() {
        final AuthenticatedPrincipal principal = new AuthenticatedPrincipal(7L, 1L, "ROLE_USER");
        when(principalResolver.resolve("valid-raw-token")).thenReturn(Optional.of(principal));

        final Message<byte[]> message = connectMessage("Bearer valid-raw-token");

        interceptor.preSend(message, channel);

        final StompHeaderAccessor result = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        assertThat(result).isNotNull();
        assertThat(result.getUser()).isNotNull();
        assertThat(result.getUser().getName()).isEqualTo("7");
    }

    @Test
    void preSend_rejectsConnect_whenTokenInvalid() {
        when(principalResolver.resolve("bad-token")).thenReturn(Optional.empty());

        final Message<byte[]> message = connectMessage("Bearer bad-token");

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void preSend_rejectsConnect_whenAuthorizationHeaderMissing() {
        final Message<byte[]> message = connectMessage(null);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void preSend_rejectsConnect_whenAuthorizationHeaderNotBearer() {
        final Message<byte[]> message = connectMessage("Basic dXNlcjpwYXNz");

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    void preSend_passesThroughUnchanged_forNonConnectCommands() {
        final StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        final Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        final Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isSameAs(message);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static Message<byte[]> connectMessage(final String authorizationHeader) {
        final StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
