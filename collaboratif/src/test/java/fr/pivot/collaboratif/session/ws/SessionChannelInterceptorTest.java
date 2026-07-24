package fr.pivot.collaboratif.session.ws;

import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionChannelInterceptor} (US19.1.2 EN19.2) — no test coverage existed
 * for this class before this PR, unlike its sibling {@code WhiteboardChannelInterceptor}, whose
 * membership-check pattern it mirrors (see {@code WhiteboardChannelInterceptorTest}). Covers both
 * accepted-principal shapes (authenticated {@link StompPrincipal} via the membership cache, and
 * self-scoped {@link SessionGuestPrincipal}), the denial path and its error notification, and the
 * pass-through short circuits for non-SUBSCRIBE commands and destinations outside this channel.
 */
@ExtendWith(MockitoExtension.class)
class SessionChannelInterceptorTest {

    private static final Long TENANT_ID = 100L;
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Long USER_ID = 7L;
    private static final String DESTINATION = "/topic/collaboratif/session/" + SESSION_ID;

    @Mock
    private SessionMembershipCacheService membershipCacheService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private SessionChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new SessionChannelInterceptor(membershipCacheService);
        ReflectionTestUtils.setField(interceptor, "messagingTemplate", messagingTemplate);
    }

    @Test
    void nonSubscribeCommandsPassThroughUnchecked() {
        Message<byte[]> sendFrame = buildFrame(StompCommand.SEND, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(sendFrame, mock(MessageChannel.class));

        assertThat(result).isSameAs(sendFrame);
        verify(membershipCacheService, never()).isMember(any(), any(), any());
    }

    @Test
    void subscribeToADestinationOutsideThisChannelPassesThroughUnchecked() {
        Message<byte[]> frame = buildFrame(
                StompCommand.SUBSCRIBE, "/topic/whiteboard/" + SESSION_ID, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isSameAs(frame);
        verify(membershipCacheService, never()).isMember(any(), any(), any());
    }

    @Test
    void authenticatedMemberIsAllowedToSubscribe() {
        when(membershipCacheService.isMember(TENANT_ID, SESSION_ID, USER_ID)).thenReturn(true);
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void authenticatedNonMemberIsDeniedAndNotified() {
        when(membershipCacheService.isMember(TENANT_ID, SESSION_ID, USER_ID)).thenReturn(false);
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq(USER_ID.toString()), eq("/queue/errors"), payload.capture());
        assertThat(payload.getValue().toString()).contains(SESSION_ID.toString());
    }

    @Test
    void guestScopedToTheSubscribedSessionIsAllowed() {
        UUID participantId = UUID.randomUUID();
        Message<byte[]> frame = buildFrame(
                StompCommand.SUBSCRIBE, DESTINATION, new SessionGuestPrincipal(SESSION_ID, participantId));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verify(membershipCacheService, never()).isMember(any(), any(), any());
    }

    @Test
    void guestScopedToADifferentSessionIsDeniedAndNotified() {
        UUID participantId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        Message<byte[]> frame = buildFrame(
                StompCommand.SUBSCRIBE, DESTINATION, new SessionGuestPrincipal(otherSessionId, participantId));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(messagingTemplate).convertAndSendToUser(
                eq("guest:" + participantId), eq("/queue/errors"), any());
    }

    /**
     * A SUBSCRIBE with no principal at all (should not occur once {@code
     * StompAuthenticationChannelInterceptor} runs first, but defensively handled here too) is
     * denied without ever touching the messaging template — {@code sendError}'s {@code user ==
     * null} guard must actually short-circuit, not merely be reachable.
     */
    @Test
    void subscribeWithNoPrincipalIsDeniedWithoutAttemptingToNotify() {
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, null);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    /**
     * When the messaging template itself throws while notifying a denied subscriber (e.g. the
     * user's session already disconnected), the denial must still be enforced: {@code preSend}
     * swallows the notification failure and still returns {@code null} rather than propagating.
     */
    @Test
    void notificationFailureDuringDenialDoesNotPreventTheSubscribeFromBeingDropped() {
        when(membershipCacheService.isMember(TENANT_ID, SESSION_ID, USER_ID)).thenReturn(false);
        org.mockito.Mockito.doThrow(new RuntimeException("broker unavailable"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    private Message<byte[]> buildFrame(
            final StompCommand command, final String destination, final java.security.Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        if (principal != null) {
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
