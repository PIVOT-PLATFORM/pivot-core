package fr.pivot.collaboratif.whiteboard.ws;

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
import org.springframework.web.socket.CloseStatus;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StompAuthenticationChannelInterceptor} — no test file existed for this
 * class before this PR despite it being the sole authentication gate for every STOMP {@code
 * CONNECT} frame on the whiteboard/session channels. Primarily targets the new US19.2.1 guest
 * fallback ({@link StompAuthenticationChannelInterceptor#preSend} delegating to the private
 * {@code authenticateGuest} when no bearer token is present) since that logic — added by this PR
 * — previously had zero coverage, but also locks down the pre-existing bearer-token accept/reject
 * paths and the non-CONNECT pass-through, since none of those had a regression net either.
 */
@ExtendWith(MockitoExtension.class)
class StompAuthenticationChannelInterceptorTest {

    private static final Long USER_ID = 1L;
    private static final Long TENANT_ID = 100L;
    private static final String SESSION_ID = "ws-session-1";
    private static final String RAW_TOKEN = "raw-bearer-token";
    private static final String GUEST_TOKEN = "raw-guest-token";

    @Mock
    private AuthenticatedPrincipalResolver principalResolver;
    @Mock
    private WhiteboardSessionRegistry sessionRegistry;
    @Mock
    private GuestPrincipalResolver guestPrincipalResolver;

    private StompAuthenticationChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthenticationChannelInterceptor(
                principalResolver, sessionRegistry, guestPrincipalResolver);
    }

    @Test
    void nonConnectCommandsPassThroughWithoutAnyAuthenticationAttempt() {
        Message<byte[]> frame = buildConnect(null, null);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(SESSION_ID);
        accessor.setLeaveMutable(true);
        Message<byte[]> sendFrame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(sendFrame, mock(MessageChannel.class));

        assertThat(result).isSameAs(sendFrame);
        verify(principalResolver, never()).resolve(any());
        verify(guestPrincipalResolver, never()).resolveGuest(any());
    }

    @Test
    void connectWithAValidBearerTokenSetsTheAuthenticatedPrincipal() {
        when(principalResolver.resolve(RAW_TOKEN))
                .thenReturn(Optional.of(new AuthenticatedPrincipal(USER_ID, TENANT_ID, "ROLE_USER")));
        Message<byte[]> frame = buildConnect("Bearer " + RAW_TOKEN, null);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isSameAs(frame);
        verify(guestPrincipalResolver, never()).resolveGuest(any());
        verify(sessionRegistry, never()).close(any(), any());
    }

    @Test
    void connectWithAnInvalidBearerTokenIsRejectedAndClosesTheSession() {
        when(principalResolver.resolve(RAW_TOKEN)).thenReturn(Optional.empty());
        Message<byte[]> frame = buildConnect("Bearer " + RAW_TOKEN, null);

        assertThatThrownBy(() -> interceptor.preSend(frame, mock(MessageChannel.class)))
                .isInstanceOf(MessagingException.class);
        verify(sessionRegistry).close(SESSION_ID, CloseStatus.NOT_ACCEPTABLE);
        verify(guestPrincipalResolver, never()).resolveGuest(any());
    }

    /**
     * The US19.2.1 fallback added by this PR: no bearer token at all, and no guest token either
     * — must still be rejected exactly like a missing bearer token used to be, not silently
     * accepted.
     */
    @Test
    void connectWithNeitherABearerNorAGuestTokenIsRejected() {
        Message<byte[]> frame = buildConnect(null, null);

        assertThatThrownBy(() -> interceptor.preSend(frame, mock(MessageChannel.class)))
                .isInstanceOf(MessagingException.class);
        verify(sessionRegistry).close(SESSION_ID, CloseStatus.NOT_ACCEPTABLE);
    }

    @Test
    void connectWithABlankGuestTokenIsRejected() {
        Message<byte[]> frame = buildConnect(null, "   ");

        assertThatThrownBy(() -> interceptor.preSend(frame, mock(MessageChannel.class)))
                .isInstanceOf(MessagingException.class);
        verify(guestPrincipalResolver, never()).resolveGuest(any());
    }

    @Test
    void connectWithAGuestTokenThatDoesNotResolveIsRejected() {
        when(guestPrincipalResolver.resolveGuest(GUEST_TOKEN)).thenReturn(Optional.empty());
        Message<byte[]> frame = buildConnect(null, GUEST_TOKEN);

        assertThatThrownBy(() -> interceptor.preSend(frame, mock(MessageChannel.class)))
                .isInstanceOf(MessagingException.class);
        verify(sessionRegistry).close(SESSION_ID, CloseStatus.NOT_ACCEPTABLE);
    }

    @Test
    void connectWithAResolvableGuestTokenSetsTheGuestPrincipalAndLetsTheFrameThrough() {
        UUID participantId = UUID.randomUUID();
        Principal guestPrincipal = () -> "guest:" + participantId;
        when(guestPrincipalResolver.resolveGuest(GUEST_TOKEN)).thenReturn(Optional.of(guestPrincipal));
        Message<byte[]> frame = buildConnect(null, GUEST_TOKEN);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isSameAs(frame);
        verify(sessionRegistry, never()).close(any(), any());
        StompHeaderAccessor resultAccessor =
                org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(
                        result, StompHeaderAccessor.class);
        assertThat(resultAccessor.getUser()).isEqualTo(guestPrincipal);
    }

    /**
     * A malformed {@code Authorization} header (present, but not a {@code Bearer } prefix) is
     * treated identically to a fully absent one — falls through to the guest path rather than
     * being rejected outright without ever consulting the guest resolver.
     */
    @Test
    void connectWithAMalformedAuthorizationHeaderFallsThroughToTheGuestPath() {
        when(guestPrincipalResolver.resolveGuest(GUEST_TOKEN)).thenReturn(Optional.empty());
        Message<byte[]> frame = buildConnect("Token " + RAW_TOKEN, GUEST_TOKEN);

        assertThatThrownBy(() -> interceptor.preSend(frame, mock(MessageChannel.class)))
                .isInstanceOf(MessagingException.class);
        verify(guestPrincipalResolver).resolveGuest(GUEST_TOKEN);
        verify(principalResolver, never()).resolve(any());
    }

    private Message<byte[]> buildConnect(final String authorizationHeader, final String guestTokenHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(SESSION_ID);
        if (authorizationHeader != null) {
            accessor.setNativeHeader("Authorization", authorizationHeader);
        }
        if (guestTokenHeader != null) {
            accessor.setNativeHeader("X-Guest-Token", guestTokenHeader);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
