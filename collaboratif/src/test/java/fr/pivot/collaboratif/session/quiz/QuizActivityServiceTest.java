package fr.pivot.collaboratif.session.quiz;

import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.quiz.dto.QuizResultsDto;
import fr.pivot.collaboratif.session.quiz.dto.QuizStateDto;
import fr.pivot.collaboratif.session.quiz.dto.SubmitAnswerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuizActivityServiceTest {

    private static final String CONFIG =
            "{\"questions\":["
                    + "{\"text\":\"Q1\",\"options\":[\"A\",\"B\",\"C\"],\"correctIndices\":[1],\"durationSeconds\":30},"
                    + "{\"text\":\"Q2\",\"options\":[\"X\",\"Y\"],\"correctIndices\":[0],\"durationSeconds\":30}]}";

    @Mock
    private SessionQuizStateRepository stateRepository;
    @Mock
    private SessionQuizAnswerRepository answerRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private QuizActivityService service;

    @BeforeEach
    void setUp() {
        service = new QuizActivityService(
                stateRepository, answerRepository, participantRepository, messagingTemplate, new ObjectMapper());
    }

    private Session liveQuiz() {
        Session session = new Session(1L, null, "T", SessionType.QUIZ, "ABCDEF", CONFIG, 10L, Instant.now());
        session.setStatus(SessionStatus.LIVE);
        return session;
    }

    private SessionQuizState stateAt(final UUID sessionId, final int index, final Instant startedAt, final boolean ended) {
        SessionQuizState state = new SessionQuizState(sessionId);
        if (index >= 0) {
            state.startQuestion(index, startedAt);
            if (ended) {
                state.endQuestion();
            }
        }
        return state;
    }

    // --- next ---------------------------------------------------------------------------------

    @Test
    void nextRejectsWhenSessionIsNotLive() {
        Session session = new Session(1L, null, "T", SessionType.QUIZ, "ABCDEF", CONFIG, 10L, Instant.now());
        assertThatThrownBy(() -> service.next(session)).isInstanceOf(InvalidSessionStatusException.class);
    }

    @Test
    void nextOpensTheFirstQuestionAndBroadcasts() {
        Session session = liveQuiz();
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());

        service.next(session);

        ArgumentCaptor<SessionQuizState> captor = ArgumentCaptor.forClass(SessionQuizState.class);
        verify(stateRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrentQuestionIndex()).isZero();
        assertThat(captor.getValue().isQuestionEnded()).isFalse();
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void nextRejectsWhenTheQuizIsFinished() {
        Session session = liveQuiz();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 1, Instant.now(), true)));

        assertThatThrownBy(() -> service.next(session))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("QUIZ_FINISHED"));
    }

    // --- answer -------------------------------------------------------------------------------

    @Test
    void answerRejectsAnAnswerForANonLiveQuestion() {
        Session session = liveQuiz();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 0, Instant.now(), false)));

        assertThatThrownBy(() -> service.answer(session, UUID.randomUUID(), new SubmitAnswerRequest(1, List.of(0))))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void answerRejectsAfterTheQuestionEnded() {
        Session session = liveQuiz();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 0, Instant.now(), true)));

        assertThatThrownBy(() -> service.answer(session, UUID.randomUUID(), new SubmitAnswerRequest(0, List.of(1))))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("QUESTION_CLOSED"));
    }

    @Test
    void answerRejectsALateAnswerPastTheWindow() {
        Session session = liveQuiz();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 0, Instant.now().minusSeconds(60), false)));

        assertThatThrownBy(() -> service.answer(session, UUID.randomUUID(), new SubmitAnswerRequest(0, List.of(1))))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("QUESTION_CLOSED"));
        verify(answerRepository, never()).save(any());
    }

    @Test
    void answerRejectsADoubleAnswer() {
        Session session = liveQuiz();
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 0, Instant.now(), false)));
        when(answerRepository.existsBySessionIdAndParticipantIdAndQuestionIndex(session.getId(), pid, 0))
                .thenReturn(true);

        assertThatThrownBy(() -> service.answer(session, pid, new SubmitAnswerRequest(0, List.of(1))))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("ALREADY_ANSWERED"));
    }

    @Test
    void answerScoresACorrectAnswerWithTheFullSpeedBonusForTheFirstResponder() {
        Session session = liveQuiz();
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 0, Instant.now(), false)));
        when(answerRepository.existsBySessionIdAndParticipantIdAndQuestionIndex(session.getId(), pid, 0))
                .thenReturn(false);
        when(answerRepository.countBySessionIdAndQuestionIndexAndCorrectTrue(session.getId(), 0)).thenReturn(0L);
        when(answerRepository.findAllBySessionIdAndQuestionIndex(session.getId(), 0)).thenReturn(List.of());

        service.answer(session, pid, new SubmitAnswerRequest(0, List.of(1)));

        ArgumentCaptor<SessionQuizAnswer> captor = ArgumentCaptor.forClass(SessionQuizAnswer.class);
        verify(answerRepository).save(captor.capture());
        assertThat(captor.getValue().isCorrect()).isTrue();
        assertThat(captor.getValue().getPointsAwarded()).isEqualTo(150); // 100 base + 50 bonus (rank 1)
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void answerSpeedBonusDecreasesWithSubmissionRank() {
        Session session = liveQuiz();
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 0, Instant.now(), false)));
        when(answerRepository.existsBySessionIdAndParticipantIdAndQuestionIndex(session.getId(), pid, 0))
                .thenReturn(false);
        when(answerRepository.countBySessionIdAndQuestionIndexAndCorrectTrue(session.getId(), 0)).thenReturn(1L);
        when(answerRepository.findAllBySessionIdAndQuestionIndex(session.getId(), 0)).thenReturn(List.of());

        service.answer(session, pid, new SubmitAnswerRequest(0, List.of(1)));

        ArgumentCaptor<SessionQuizAnswer> captor = ArgumentCaptor.forClass(SessionQuizAnswer.class);
        verify(answerRepository).save(captor.capture());
        assertThat(captor.getValue().getPointsAwarded()).isEqualTo(140); // 100 + 40 bonus (rank 2)
    }

    @Test
    void answerScoresZeroForAWrongAnswer() {
        Session session = liveQuiz();
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 0, Instant.now(), false)));
        when(answerRepository.existsBySessionIdAndParticipantIdAndQuestionIndex(session.getId(), pid, 0))
                .thenReturn(false);
        when(answerRepository.findAllBySessionIdAndQuestionIndex(session.getId(), 0)).thenReturn(List.of());

        service.answer(session, pid, new SubmitAnswerRequest(0, List.of(2)));

        ArgumentCaptor<SessionQuizAnswer> captor = ArgumentCaptor.forClass(SessionQuizAnswer.class);
        verify(answerRepository).save(captor.capture());
        assertThat(captor.getValue().isCorrect()).isFalse();
        assertThat(captor.getValue().getPointsAwarded()).isZero();
    }

    // --- end ----------------------------------------------------------------------------------

    @Test
    void endCurrentRejectsWhenNoQuestionStarted() {
        Session session = liveQuiz();
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.endCurrent(session)).isInstanceOf(SessionValidationException.class);
    }

    @Test
    void endCurrentMarksEndedRevealsAndBroadcasts() {
        Session session = liveQuiz();
        SessionQuizState state = stateAt(session.getId(), 0, Instant.now(), false);
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.of(state));
        when(answerRepository.findAllBySessionId(session.getId())).thenReturn(List.of());
        when(participantRepository.findAllById(any())).thenReturn(List.of());

        service.endCurrent(session);

        assertThat(state.isQuestionEnded()).isTrue();
        verify(stateRepository).save(state);
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- state / results ----------------------------------------------------------------------

    @Test
    void getStateReportsNotStartedBeforeTheFirstQuestion() {
        Session session = liveQuiz();
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());
        when(answerRepository.findAllBySessionId(session.getId())).thenReturn(List.of());

        QuizStateDto state = service.getState(session, UUID.randomUUID());

        assertThat(state.started()).isFalse();
        assertThat(state.totalQuestions()).isEqualTo(2);
    }

    @Test
    void getStateWithholdsTheCorrectAnswerWhileTheQuestionIsLive() {
        Session session = liveQuiz();
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(stateAt(session.getId(), 0, Instant.now(), false)));
        when(answerRepository.existsBySessionIdAndParticipantIdAndQuestionIndex(session.getId(), pid, 0))
                .thenReturn(false);
        when(answerRepository.findAllBySessionId(session.getId())).thenReturn(List.of());

        QuizStateDto state = service.getState(session, pid);

        assertThat(state.started()).isTrue();
        assertThat(state.questionText()).isEqualTo("Q1");
        assertThat(state.correctIndices()).isEmpty();
        assertThat(state.leaderboard()).isEmpty();
    }

    @Test
    void getResultsComputesPerQuestionCorrectRate() {
        Session session = liveQuiz();
        when(answerRepository.findAllBySessionIdAndQuestionIndex(session.getId(), 0)).thenReturn(List.of(
                new SessionQuizAnswer(session.getId(), UUID.randomUUID(), 0, "[1]", true, 150, Instant.now()),
                new SessionQuizAnswer(session.getId(), UUID.randomUUID(), 0, "[2]", false, 0, Instant.now())));
        when(answerRepository.findAllBySessionIdAndQuestionIndex(session.getId(), 1)).thenReturn(List.of());
        when(answerRepository.findAllBySessionId(session.getId())).thenReturn(List.of());
        when(participantRepository.findAllById(any())).thenReturn(List.of());

        QuizResultsDto results = service.getResults(session);

        assertThat(results.correctRatePerQuestion()).containsExactly(0.5, 0.0);
    }
}
