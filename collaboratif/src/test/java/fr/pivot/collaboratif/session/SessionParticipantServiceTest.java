package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.exception.SessionGuestExpiredException;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.collaboratif.session.dto.GuestHeartbeatRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionResponse;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionParticipantServiceTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private SessionParticipantService service;

    @BeforeEach
    void setUp() {
        service = new SessionParticipantService(sessionRepository, participantRepository, messagingTemplate);
    }

    private Session session() {
        return new Session(1L, null, "T", SessionType.POLL, "ABCDEF", "{}", 10L, Instant.now());
    }

    @Test
    void joinTreatsAnUnknownCodeAsNotFound() {
        when(sessionRepository.findFirstByJoinCodeAndStatusNot(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join(new JoinSessionRequest("ZZZZZZ", "Alice"), Optional.empty()))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void joinAsAnAuthenticatedUserSetsUserIdAndOmitsTheToken() {
        Session session = session();
        when(sessionRepository.findFirstByJoinCodeAndStatusNot("ABCDEF", SessionStatus.COMPLETED))
                .thenReturn(Optional.of(session));
        when(participantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JoinSessionResponse response = service.join(
                new JoinSessionRequest("ABCDEF", "Alice"),
                Optional.of(new AuthenticatedPrincipal(10L, 1L, "ROLE_USER")));

        assertThat(response.token()).isNull();
        assertThat(response.wsTopic()).startsWith("/topic/collaboratif/session/");
    }

    @Test
    void joinAsAGuestIssuesASealedToken() {
        Session session = session();
        when(sessionRepository.findFirstByJoinCodeAndStatusNot("ABCDEF", SessionStatus.COMPLETED))
                .thenReturn(Optional.of(session));
        when(participantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        JoinSessionResponse response = service.join(new JoinSessionRequest("ABCDEF", "Bob"), Optional.empty());

        assertThat(response.token()).isNotBlank();
    }

    @Test
    void joinRejectsAnAuthenticatedCallerFromAnotherTenant() {
        Session session = session();
        when(sessionRepository.findFirstByJoinCodeAndStatusNot("ABCDEF", SessionStatus.COMPLETED))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.join(
                new JoinSessionRequest("ABCDEF", "Alice"),
                Optional.of(new AuthenticatedPrincipal(99L, 2L, "ROLE_USER"))))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void heartbeatRejectsAnUnknownParticipant() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(participantRepository.findByIdAndSessionId(participantId, sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.heartbeat(sessionId, participantId, new GuestHeartbeatRequest("token")))
                .isInstanceOf(SessionGuestExpiredException.class);
    }

    @Test
    void heartbeatRejectsAnAuthenticatedParticipant() {
        UUID sessionId = UUID.randomUUID();
        Participant participant = new Participant(sessionId, 10L, null, "Alice", Instant.now());
        when(participantRepository.findByIdAndSessionId(participant.getId(), sessionId))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> service.heartbeat(
                sessionId, participant.getId(), new GuestHeartbeatRequest("token")))
                .isInstanceOf(SessionGuestExpiredException.class);
    }

    @Test
    void heartbeatRejectsTheWrongToken() {
        UUID sessionId = UUID.randomUUID();
        Participant participant = new Participant(sessionId, null, "real-token", "Bob", Instant.now());
        when(participantRepository.findByIdAndSessionId(participant.getId(), sessionId))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> service.heartbeat(
                sessionId, participant.getId(), new GuestHeartbeatRequest("wrong-token")))
                .isInstanceOf(SessionGuestExpiredException.class);
    }

    @Test
    void heartbeatRejectsAStaleGuest() {
        UUID sessionId = UUID.randomUUID();
        Instant staleTime = Instant.now().minusSeconds(600);
        Participant participant = new Participant(sessionId, null, "real-token", "Bob", staleTime);
        when(participantRepository.findByIdAndSessionId(participant.getId(), sessionId))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> service.heartbeat(
                sessionId, participant.getId(), new GuestHeartbeatRequest("real-token")))
                .isInstanceOf(SessionGuestExpiredException.class);
    }

    @Test
    void heartbeatRefreshesAFreshGuest() {
        UUID sessionId = UUID.randomUUID();
        Participant participant = new Participant(sessionId, null, "real-token", "Bob", Instant.now());
        when(participantRepository.findByIdAndSessionId(participant.getId(), sessionId))
                .thenReturn(Optional.of(participant));
        when(participantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.heartbeat(sessionId, participant.getId(), new GuestHeartbeatRequest("real-token"));

        assertThat(participant.getLastHeartbeatAt()).isNotNull();
    }
}
