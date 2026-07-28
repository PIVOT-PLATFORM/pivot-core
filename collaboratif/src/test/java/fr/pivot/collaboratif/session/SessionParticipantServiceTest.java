package fr.pivot.collaboratif.session;

import tools.jackson.databind.ObjectMapper;
import fr.pivot.collaboratif.exception.RoomFullException;
import fr.pivot.collaboratif.exception.SessionGuestExpiredException;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.dto.GuestHeartbeatRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionRequest;
import fr.pivot.collaboratif.session.dto.JoinSessionResponse;
import fr.pivot.collaboratif.session.dto.ParticipantSessionResponse;
import fr.pivot.collaboratif.session.postitrush.PostitRushConstants;
import fr.pivot.core.auth.AuthenticatedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new SessionParticipantService(
                sessionRepository, participantRepository, messagingTemplate, objectMapper);
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

    // --- US47.2.1 POSTIT_RUSH join extension (type-gated, no other session type affected) ------

    private Session postitRushSession() {
        return new Session(1L, null, "Rush", SessionType.POSTIT_RUSH, "ABCDEF", "{}", 10L, Instant.now());
    }

    @Test
    void joinRejectsADisplayNameAlreadyUsedInAPostitRushRoom() {
        Session session = postitRushSession();
        when(sessionRepository.findFirstByJoinCodeAndStatusNot("ABCDEF", SessionStatus.COMPLETED))
                .thenReturn(Optional.of(session));
        when(participantRepository.existsBySessionIdAndDisplayNameIgnoreCase(session.getId(), "Alice"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.join(new JoinSessionRequest("ABCDEF", "Alice"), Optional.empty()))
                .isInstanceOf(SessionValidationException.class)
                .satisfies(ex -> assertThat(((SessionValidationException) ex).getCode()).isEqualTo("INVALID_DISPLAY_NAME"));
    }

    @Test
    void joinAllowsTheSameDisplayNameOnANonPostitRushSession() {
        Session session = session(); // POLL, per the base fixture
        when(sessionRepository.findFirstByJoinCodeAndStatusNot("ABCDEF", SessionStatus.COMPLETED))
                .thenReturn(Optional.of(session));
        when(participantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.join(new JoinSessionRequest("ABCDEF", "Alice"), Optional.empty());

        org.mockito.Mockito.verify(participantRepository, org.mockito.Mockito.never())
                .existsBySessionIdAndDisplayNameIgnoreCase(any(), anyString());
    }

    @Test
    void joinAdmitsAPlayerBelowHardCap() {
        Session session = postitRushSession();
        when(sessionRepository.findFirstByJoinCodeAndStatusNot("ABCDEF", SessionStatus.COMPLETED))
                .thenReturn(Optional.of(session));
        when(participantRepository.countBySessionId(session.getId())).thenReturn(5L);
        when(participantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.join(new JoinSessionRequest("ABCDEF", "Alice"), Optional.empty());

        ArgumentCaptor<Participant> captor = ArgumentCaptor.forClass(Participant.class);
        org.mockito.Mockito.verify(participantRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(ParticipantRole.PLAYER);
    }

    @Test
    void joinAtHardCapWithoutAcceptingSpectatorFallbackIsRejected() {
        Session session = postitRushSession();
        when(sessionRepository.findFirstByJoinCodeAndStatusNot("ABCDEF", SessionStatus.COMPLETED))
                .thenReturn(Optional.of(session));
        when(participantRepository.countBySessionId(session.getId()))
                .thenReturn((long) PostitRushConstants.HARD_CAP);

        assertThatThrownBy(() -> service.join(new JoinSessionRequest("ABCDEF", "Zoe", null), Optional.empty()))
                .isInstanceOf(RoomFullException.class);
    }

    @Test
    void joinAtHardCapWithSpectatorFallbackAcceptedAdmitsAsSpectator() {
        Session session = postitRushSession();
        when(sessionRepository.findFirstByJoinCodeAndStatusNot("ABCDEF", SessionStatus.COMPLETED))
                .thenReturn(Optional.of(session));
        when(participantRepository.countBySessionId(session.getId()))
                .thenReturn((long) PostitRushConstants.HARD_CAP);
        when(participantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.join(new JoinSessionRequest("ABCDEF", "Zoe", true), Optional.empty());

        ArgumentCaptor<Participant> captor = ArgumentCaptor.forClass(Participant.class);
        org.mockito.Mockito.verify(participantRepository).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(ParticipantRole.SPECTATOR);
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

    @Test
    void getForParticipantReturnsTheParticipantSafeShapeIncludingConfigAndCount() {
        Session session = new Session(
                1L, 42L, "Retro", SessionType.POLL, "ABCDEF",
                "{\"question\":\"Q\",\"options\":[\"A\",\"B\"]}", 10L, Instant.now());
        when(participantRepository.countBySessionId(session.getId())).thenReturn(3L);

        ParticipantSessionResponse response = service.getForParticipant(session);

        assertThat(response.id()).isEqualTo(session.getId());
        assertThat(response.title()).isEqualTo("Retro");
        assertThat(response.type()).isEqualTo(SessionType.POLL);
        assertThat(response.status()).isEqualTo(session.getStatus());
        assertThat(response.participantCount()).isEqualTo(3L);
        assertThat(response.config().get("question").asText()).isEqualTo("Q");
    }

    @Test
    void getForParticipantNeverExposesJoinCodeOrTeamId() {
        Session session = new Session(1L, 42L, "Retro", SessionType.WORDCLOUD, "SECRET", "{}", 10L, Instant.now());
        when(participantRepository.countBySessionId(session.getId())).thenReturn(0L);

        ParticipantSessionResponse response = service.getForParticipant(session);

        // Asserts the record's exact field set (rather than a doesNotContain check on a
        // collection that could trivially pass if empty) — documents the intentional shape gap
        // (no joinCode/teamId accessor at all) in case a future edit widens the record.
        assertThat(response.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder(
                        "id", "title", "type", "status", "config", "participantCount", "startedAt", "endedAt");
    }
}
