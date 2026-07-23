package fr.pivot.collaboratif.session;

import fr.pivot.collaboratif.context.CollaboratifRequestPrincipal;
import fr.pivot.collaboratif.exception.InvalidSessionTransitionException;
import fr.pivot.collaboratif.session.dto.CreateSessionRequest;
import fr.pivot.collaboratif.session.dto.SessionResponse;
import fr.pivot.collaboratif.session.poll.PollActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private static final Long TENANT_ID = 1L;
    private static final Long CREATOR_ID = 10L;

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private ActivityRepository activityRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private SessionAccessService accessService;
    @Mock
    private JoinCodeGenerator joinCodeGenerator;
    @Mock
    private PollActivityService pollActivityService;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private SessionService sessionService;
    private ObjectMapper objectMapper;
    private CollaboratifRequestPrincipal principal;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sessionService = new SessionService(
                sessionRepository, activityRepository, participantRepository, accessService,
                joinCodeGenerator, pollActivityService, messagingTemplate, objectMapper);
        principal = new CollaboratifRequestPrincipal(CREATOR_ID, TENANT_ID, "ROLE_USER");
    }

    private Session session(final SessionStatus status) {
        Session session = new Session(
                TENANT_ID, null, "Title", SessionType.WORDCLOUD, "ABCDEF", "{}", CREATOR_ID, Instant.now());
        session.setStatus(status);
        return session;
    }

    @Test
    void createGeneratesAJoinCodeAndPersistsADraftSession() {
        when(joinCodeGenerator.generate(TENANT_ID)).thenReturn("ABCDEF");
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pollActivityService.supports(SessionType.WORDCLOUD)).thenReturn(false);
        when(participantRepository.countBySessionId(any())).thenReturn(0L);
        CreateSessionRequest request = new CreateSessionRequest(
                "Title", SessionType.WORDCLOUD, objectMapper.readTree("{\"maxWordsPerParticipant\":3}"), null);

        SessionResponse response = sessionService.create(request, principal);

        assertThat(response.title()).isEqualTo("Title");
        assertThat(response.status()).isEqualTo(SessionStatus.DRAFT);
        assertThat(response.joinCode()).isEqualTo("ABCDEF");
        verify(activityRepository).save(any());
    }

    @Test
    void createInitializesPollOptionsWhenTypeIsPoll() {
        when(joinCodeGenerator.generate(TENANT_ID)).thenReturn("ABCDEF");
        when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pollActivityService.supports(SessionType.POLL)).thenReturn(true);
        when(participantRepository.countBySessionId(any())).thenReturn(0L);
        CreateSessionRequest request = new CreateSessionRequest(
                "Title", SessionType.POLL,
                objectMapper.readTree("{\"question\":\"Q\",\"options\":[\"A\",\"B\"]}"), null);

        sessionService.create(request, principal);

        verify(pollActivityService).initializeFromConfig(any(), any());
    }

    @Test
    void listFiltersByTeamIdAndStatus() {
        Session visible = session(SessionStatus.DRAFT);
        when(sessionRepository.findAllByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(visible));
        when(accessService.isVisibleToCaller(visible, principal)).thenReturn(true);
        when(participantRepository.countBySessionId(any())).thenReturn(0L);

        List<SessionResponse> results = sessionService.list(principal, null, SessionStatus.DRAFT);

        assertThat(results).hasSize(1);
    }

    @Test
    void listExcludesSessionsInvisibleToTheCaller() {
        Session hidden = session(SessionStatus.DRAFT);
        when(sessionRepository.findAllByTenantIdOrderByCreatedAtDesc(TENANT_ID)).thenReturn(List.of(hidden));
        when(accessService.isVisibleToCaller(hidden, principal)).thenReturn(false);

        List<SessionResponse> results = sessionService.list(principal, null, null);

        assertThat(results).isEmpty();
    }

    @Test
    void startTransitionsFromDraftToLiveAndBroadcasts() {
        Session session = session(SessionStatus.DRAFT);
        when(accessService.resolveSessionForOwnerOrAdmin(session.getId(), principal)).thenReturn(session);
        when(participantRepository.countBySessionId(any())).thenReturn(0L);

        sessionService.start(session.getId(), principal);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.LIVE);
        assertThat(session.getStartedAt()).isNotNull();
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void startRejectsANonDraftSession() {
        Session session = session(SessionStatus.LIVE);
        when(accessService.resolveSessionForOwnerOrAdmin(session.getId(), principal)).thenReturn(session);

        assertThatThrownBy(() -> sessionService.start(session.getId(), principal))
                .isInstanceOf(InvalidSessionTransitionException.class);
    }

    @Test
    void pauseTransitionsFromLiveToPaused() {
        Session session = session(SessionStatus.LIVE);
        when(accessService.resolveSessionForOwnerOrAdmin(session.getId(), principal)).thenReturn(session);

        sessionService.pause(session.getId(), principal);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.PAUSED);
    }

    @Test
    void resumeTransitionsFromPausedToLive() {
        Session session = session(SessionStatus.PAUSED);
        when(accessService.resolveSessionForOwnerOrAdmin(session.getId(), principal)).thenReturn(session);

        sessionService.resume(session.getId(), principal);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.LIVE);
    }

    @Test
    void endTransitionsFromLiveToCompletedAndSetsEndedAt() {
        Session session = session(SessionStatus.LIVE);
        when(accessService.resolveSessionForOwnerOrAdmin(session.getId(), principal)).thenReturn(session);

        sessionService.end(session.getId(), principal);

        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getEndedAt()).isNotNull();
    }

    @Test
    void endRejectsAnAlreadyDraftSession() {
        Session session = session(SessionStatus.DRAFT);
        when(accessService.resolveSessionForOwnerOrAdmin(session.getId(), principal)).thenReturn(session);

        assertThatThrownBy(() -> sessionService.end(session.getId(), principal))
                .isInstanceOf(InvalidSessionTransitionException.class);
    }
}
