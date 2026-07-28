package fr.pivot.collaboratif.meeting.ws;

import fr.pivot.collaboratif.meeting.Meeting;
import fr.pivot.collaboratif.meeting.MeetingRepository;
import fr.pivot.collaboratif.meetops.booking.MeetingParticipantRepository;
import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeetingChannelInterceptor} (US12.2.1 AC-S3, extended by US12.4.1) —
 * mirrors {@code fr.pivot.collaboratif.session.ws.SessionChannelInterceptorTest}'s coverage shape
 * for this channel's single-principal-type authorization, plus the US12.4.1 organizer/participant
 * fallback.
 */
@ExtendWith(MockitoExtension.class)
class MeetingChannelInterceptorTest {

    private static final Long TENANT_ID = 100L;
    private static final UUID MEETING_ID = UUID.randomUUID();
    private static final Long USER_ID = 7L;
    private static final String DESTINATION = "/topic/collaboratif/meeting/" + MEETING_ID;

    @Mock
    private MeetingMembershipCacheService membershipCacheService;
    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private MeetingParticipantRepository meetingParticipantRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private MeetingChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new MeetingChannelInterceptor(
                membershipCacheService, meetingRepository, meetingParticipantRepository);
        ReflectionTestUtils.setField(interceptor, "messagingTemplate", messagingTemplate);
        lenient().when(membershipCacheService.isMember(TENANT_ID, MEETING_ID, USER_ID)).thenReturn(false);
        lenient().when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.empty());
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
                StompCommand.SUBSCRIBE, "/topic/collaboratif/session/" + MEETING_ID,
                new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isSameAs(frame);
        verify(membershipCacheService, never()).isMember(any(), any(), any());
    }

    @Test
    void authenticatedMemberIsAllowedToSubscribe() {
        when(membershipCacheService.isMember(TENANT_ID, MEETING_ID, USER_ID)).thenReturn(true);
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void authenticatedNonMemberIsDeniedAndNotified() {
        when(membershipCacheService.isMember(TENANT_ID, MEETING_ID, USER_ID)).thenReturn(false);
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(eq(USER_ID.toString()), eq("/queue/errors"), payload.capture());
        assertThat(payload.getValue().toString()).contains(MEETING_ID.toString());
    }

    @Test
    void subscribeWithNoPrincipalIsDeniedWithoutAttemptingToNotify() {
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, null);

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    /**
     * When the messaging template itself throws while notifying a denied subscriber, the denial
     * must still be enforced: {@code preSend} swallows the notification failure and still
     * returns {@code null} rather than propagating.
     */
    @Test
    void notificationFailureDuringDenialDoesNotPreventTheSubscribeFromBeingDropped() {
        when(membershipCacheService.isMember(TENANT_ID, MEETING_ID, USER_ID)).thenReturn(false);
        doThrow(new RuntimeException("broker unavailable"))
                .when(messagingTemplate).convertAndSendToUser(anyString(), anyString(), any());
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    @Test
    void nonMemberOrganizerIsAllowedToSubscribe() {
        Meeting meeting = mock(Meeting.class);
        when(meeting.getCreatedBy()).thenReturn(USER_ID);
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void nonMemberResolvedParticipantIsAllowedToSubscribe() {
        Meeting meeting = mock(Meeting.class);
        when(meeting.getCreatedBy()).thenReturn(999L);
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.existsByMeetingIdAndParticipantUserId(MEETING_ID, USER_ID)).thenReturn(true);
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNotNull();
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void nonMemberNonOrganizerNonParticipantIsDenied() {
        Meeting meeting = mock(Meeting.class);
        when(meeting.getCreatedBy()).thenReturn(999L);
        when(meetingRepository.findByIdAndTenantId(MEETING_ID, TENANT_ID)).thenReturn(Optional.of(meeting));
        when(meetingParticipantRepository.existsByMeetingIdAndParticipantUserId(MEETING_ID, USER_ID)).thenReturn(false);
        Message<byte[]> frame = buildFrame(StompCommand.SUBSCRIBE, DESTINATION, new StompPrincipal(USER_ID, TENANT_ID));

        Message<?> result = interceptor.preSend(frame, mock(MessageChannel.class));

        assertThat(result).isNull();
    }

    private Message<byte[]> buildFrame(final StompCommand command, final String destination, final Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        if (principal != null) {
            accessor.setUser(principal);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
