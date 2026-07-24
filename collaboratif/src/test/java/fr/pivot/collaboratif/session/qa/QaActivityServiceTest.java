package fr.pivot.collaboratif.session.qa;

import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionNotFoundException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Participant;
import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.qa.dto.QaQuestionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

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
class QaActivityServiceTest {

    @Mock
    private SessionQaQuestionRepository questionRepository;
    @Mock
    private SessionQaUpvoteRepository upvoteRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private QaActivityService service;

    @BeforeEach
    void setUp() {
        service = new QaActivityService(
                questionRepository, upvoteRepository, participantRepository, messagingTemplate);
    }

    private Session liveSession() {
        Session session = new Session(1L, null, "T", SessionType.QA, "ABCDEF", "{}", 10L, Instant.now());
        session.setStatus(SessionStatus.LIVE);
        return session;
    }

    private SessionQaQuestion question(final UUID id, final UUID sessionId, final boolean anonymous, final Instant at) {
        SessionQaQuestion q = new SessionQaQuestion(sessionId, UUID.randomUUID(), "Q?", anonymous, at);
        ReflectionTestUtils.setField(q, "id", id);
        return q;
    }

    // --- submit -------------------------------------------------------------------------------

    @Test
    void submitQuestionRejectsWhenSessionIsNotLive() {
        Session session = new Session(1L, null, "T", SessionType.QA, "ABCDEF", "{}", 10L, Instant.now());

        assertThatThrownBy(() -> service.submitQuestion(session, UUID.randomUUID(), "Q?", false))
                .isInstanceOf(InvalidSessionStatusException.class);
        verify(questionRepository, never()).save(any());
    }

    @Test
    void submitQuestionRejectsEmptyText() {
        assertThatThrownBy(() -> service.submitQuestion(liveSession(), UUID.randomUUID(), "   ", false))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void submitQuestionRejectsTextOver500Chars() {
        String tooLong = "x".repeat(501);
        assertThatThrownBy(() -> service.submitQuestion(liveSession(), UUID.randomUUID(), tooLong, false))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void submitQuestionPersistsAndBroadcastsWithAuthorName() {
        Session session = liveSession();
        UUID participantId = UUID.randomUUID();
        Participant author = new Participant(session.getId(), 7L, null, "Alice", Instant.now());
        when(participantRepository.findById(participantId)).thenReturn(Optional.of(author));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitQuestion(session, participantId, "  Why? ", false);

        verify(questionRepository).save(any());
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void submitQuestionAnonymousDoesNotResolveTheAuthorName() {
        Session session = liveSession();
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.submitQuestion(session, UUID.randomUUID(), "Anon?", true);

        verify(participantRepository, never()).findById(any());
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- upvote -------------------------------------------------------------------------------

    @Test
    void upvoteRejectsWhenSessionIsNotLive() {
        Session session = new Session(1L, null, "T", SessionType.QA, "ABCDEF", "{}", 10L, Instant.now());

        assertThatThrownBy(() -> service.upvote(session, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(InvalidSessionStatusException.class);
    }

    @Test
    void upvoteRejectsAnUnknownQuestion() {
        Session session = liveSession();
        UUID questionId = UUID.randomUUID();
        when(questionRepository.findByIdAndSessionId(questionId, session.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upvote(session, UUID.randomUUID(), questionId))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void upvoteRejectsADoubleVoteFromTheSameParticipant() {
        Session session = liveSession();
        UUID questionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(questionRepository.findByIdAndSessionId(questionId, session.getId()))
                .thenReturn(Optional.of(question(questionId, session.getId(), false, Instant.now())));
        when(upvoteRepository.existsByQuestionIdAndParticipantId(questionId, participantId)).thenReturn(true);

        assertThatThrownBy(() -> service.upvote(session, participantId, questionId))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("ALREADY_UPVOTED"));
        verify(upvoteRepository, never()).save(any());
    }

    @Test
    void upvoteRecordsAndBroadcastsTheNewCount() {
        Session session = liveSession();
        UUID questionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(questionRepository.findByIdAndSessionId(questionId, session.getId()))
                .thenReturn(Optional.of(question(questionId, session.getId(), false, Instant.now())));
        when(upvoteRepository.existsByQuestionIdAndParticipantId(questionId, participantId)).thenReturn(false);
        when(upvoteRepository.countByQuestionId(questionId)).thenReturn(3L);

        service.upvote(session, participantId, questionId);

        verify(upvoteRepository).save(any());
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- markAnswered -------------------------------------------------------------------------

    @Test
    void markAnsweredRejectsAnUnknownQuestion() {
        UUID sessionId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        when(questionRepository.findByIdAndSessionId(questionId, sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markAnswered(sessionId, questionId))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void markAnsweredSetsTheFlagAndBroadcasts() {
        UUID sessionId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        SessionQaQuestion q = question(questionId, sessionId, false, Instant.now());
        when(questionRepository.findByIdAndSessionId(questionId, sessionId)).thenReturn(Optional.of(q));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.markAnswered(sessionId, questionId);

        assertThat(q.isAnswered()).isTrue();
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- list ---------------------------------------------------------------------------------

    @Test
    void getQuestionsSortsByUpvotesDescThenOldestFirst() {
        UUID sessionId = UUID.randomUUID();
        UUID q1 = UUID.randomUUID();
        UUID q2 = UUID.randomUUID();
        UUID q3 = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        SessionQaQuestion question1 = question(q1, sessionId, false, t0);              // 1 upvote
        SessionQaQuestion question2 = question(q2, sessionId, false, t0.plusSeconds(10)); // 2 upvotes
        SessionQaQuestion question3 = question(q3, sessionId, false, t0.plusSeconds(20)); // 2 upvotes, later
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of(question1, question2, question3));
        when(upvoteRepository.findAllByQuestionIdIn(any())).thenReturn(List.of(
                new SessionQaUpvote(q1, UUID.randomUUID(), t0),
                new SessionQaUpvote(q2, UUID.randomUUID(), t0),
                new SessionQaUpvote(q2, UUID.randomUUID(), t0),
                new SessionQaUpvote(q3, UUID.randomUUID(), t0),
                new SessionQaUpvote(q3, UUID.randomUUID(), t0)));
        when(participantRepository.findAllById(any())).thenReturn(List.of());

        List<QaQuestionDto> result = service.getQuestions(sessionId);

        // q2 and q3 both have 2 upvotes → q2 first (older); q1 last with 1 upvote.
        assertThat(result).extracting(QaQuestionDto::id).containsExactly(q2, q3, q1);
        assertThat(result).extracting(QaQuestionDto::upvotes).containsExactly(2L, 2L, 1L);
    }

    @Test
    void getQuestionsWithholdsAnonymousAuthorNames() {
        UUID sessionId = UUID.randomUUID();
        UUID qid = UUID.randomUUID();
        SessionQaQuestion anon = question(qid, sessionId, true, Instant.now());
        when(questionRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(anon));
        when(upvoteRepository.findAllByQuestionIdIn(any())).thenReturn(List.of());
        when(participantRepository.findAllById(any())).thenReturn(List.of());

        List<QaQuestionDto> result = service.getQuestions(sessionId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).anonymous()).isTrue();
        assertThat(result.get(0).authorName()).isNull();
    }
}
