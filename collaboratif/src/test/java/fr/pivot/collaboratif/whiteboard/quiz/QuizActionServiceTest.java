package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CanvasActionMessage;
import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static fr.pivot.collaboratif.whiteboard.canvas.BroadcastPayloads.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QuizActionService} (Lot D1, whiteboard quiz activity), mirroring the
 * plain-Mockito unit-test pattern used for {@code RetroVoteService}/{@code PokerVoteService}
 * rather than the full-Testcontainers STOMP round trip of {@code VoteActionIT} — every
 * collaborator (repositories, {@link SimpMessagingTemplate}) is mocked, and a real {@link
 * ObjectMapper} is used so the broadcast payload assembled by {@code
 * QuizActionService#broadcast} is inspected exactly as the frontend would receive it.
 *
 * <p><strong>AC → test mapping (§2.3, §7.3 of {@code QUIZ-ACTIVITY-DESIGN.md}):</strong>
 * <ul>
 *   <li>US-Q1 happy path → {@link #start_validPayloadByManager_createsSessionOpensFirstQuestionAndBroadcastsMaskedSessionStarted()}</li>
 *   <li>US-Q1 double-active → {@link #start_activeSessionAlreadyExists_droppedWithoutPersistOrBroadcast()}</li>
 *   <li>US-Q1 invalid payload → {@code start_zeroQuestions_*}/{@code start_choiceCountBelowTwo_*}/
 *       {@code start_noCorrectChoice_*}/{@code start_blankQuestionText_*}</li>
 *   <li>US-Q1 security (VIEWER) → {@link #start_viewerCannotManage_droppedWithoutPersistOrBroadcast()}</li>
 *   <li>US-Q1 race on unique index → {@link #start_lostRaceOnUniqueActiveIndex_droppedSilently()}</li>
 *   <li>US-Q2 upsert/replace → {@code answer_newAnswer_*}/{@code answer_existingAnswer_*}</li>
 *   <li>US-Q2 error cases → {@code answer_choiceDoesNotBelongToCurrentQuestion_*}/
 *       {@code answer_questionAlreadyRevealed_*}/{@code answer_quizNotStartedYet_*}/
 *       {@code answer_staleQuestionId_*}</li>
 *   <li>US-Q2 security (masking) → every {@code assertNoCorrectKeyAnywhere} call below, and
 *       specifically {@link #start_validPayloadByManager_createsSessionOpensFirstQuestionAndBroadcastsMaskedSessionStarted()}
 *       / {@link #answer_newAnswer_upsertsAndBroadcastsAnsweredCountOnlyMasked()}</li>
 *   <li>US-Q3 next/D3 → {@code next_managerAdvancesToNextQuestion_*}/{@code next_noFurtherQuestion_*}</li>
 *   <li>US-Q3 reveal → {@code reveal_managerRevealsOpenQuestion_*}/{@code reveal_noOpenQuestion_*}</li>
 *   <li>US-Q3 security (canManage) → {@code next_viewerCannotManage_*}/{@code reveal_viewerCannotManage_*}</li>
 *   <li>US-Q4 stop/leaderboard → {@code stop_managerClosesSession_*}/{@code stop_viewerCannotManage_*}</li>
 * </ul>
 */
class QuizActionServiceTest {

    private static final UUID BOARD_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Long TENANT_ID = 100L;
    private static final Long USER_ID = 42L;

    private SimpMessagingTemplate messagingTemplate;
    private QuizSessionRepository quizSessionRepository;
    private QuestionRepository questionRepository;
    private ChoiceRepository choiceRepository;
    private AnswerRepository answerRepository;
    private BoardMemberRepository boardMemberRepository;
    private QuizActionService service;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        quizSessionRepository = mock(QuizSessionRepository.class);
        questionRepository = mock(QuestionRepository.class);
        choiceRepository = mock(ChoiceRepository.class);
        answerRepository = mock(AnswerRepository.class);
        boardMemberRepository = mock(BoardMemberRepository.class);
        service = new QuizActionService(
                messagingTemplate, quizSessionRepository, questionRepository, choiceRepository,
                answerRepository, boardMemberRepository, new ObjectMapper());
    }

    // =========================================================================
    // quiz:start
    // =========================================================================

    @Test
    void start_validPayloadByManager_createsSessionOpensFirstQuestionAndBroadcastsMaskedSessionStarted() {
        stubManager(BoardRole.OWNER);
        when(quizSessionRepository.existsByBoardIdAndStatus(BOARD_ID, QuizStatus.ACTIVE)).thenReturn(false);

        UUID questionId = UUID.randomUUID();
        when(quizSessionRepository.save(any(QuizSession.class))).thenAnswer(inv -> {
            QuizSession s = inv.getArgument(0);
            setId(s, SESSION_ID);
            return s;
        });
        when(questionRepository.save(any(Question.class))).thenAnswer(inv -> {
            Question q = inv.getArgument(0);
            setId(q, questionId);
            return q;
        });
        when(quizSessionRepository.saveAndFlush(any(QuizSession.class))).thenAnswer(inv -> inv.getArgument(0));

        Question persisted = question(questionId, SESSION_ID, 0, "2+2?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0)).thenReturn(Optional.of(persisted));
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(questionId)))
                .thenReturn(List.of(
                        choice(UUID.randomUUID(), questionId, 0, "3", false),
                        choice(UUID.randomUUID(), questionId, 1, "4", true)));
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, questionId)).thenReturn(0L);
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(persisted));

        service.handle(BOARD_ID, new CanvasActionMessage("quiz:start", validStartPayload()), principal());

        verify(quizSessionRepository).saveAndFlush(any(QuizSession.class));
        verify(questionRepository).save(any(Question.class));
        verify(choiceRepository, times(2)).save(any(Choice.class));

        ArgumentCaptor<BroadcastCanvasMessage> captor = ArgumentCaptor.forClass(BroadcastCanvasMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/whiteboard/" + BOARD_ID), captor.capture());
        BroadcastCanvasMessage broadcast = captor.getValue();
        assertThat(broadcast.type()).isEqualTo("quiz:session:started");
        Map<String, Object> payload = map(broadcast);
        assertThat(payload.get("status")).isEqualTo("ACTIVE");
        assertNoCorrectKeyAnywhere(payload);
        Map<String, Object> currentQuestion = asMap(payload.get("currentQuestion"));
        assertThat(currentQuestion.get("state")).isEqualTo("OPEN");
        assertThat(((Number) currentQuestion.get("answeredCount")).intValue()).isZero();
    }

    @Test
    void start_activeSessionAlreadyExists_droppedWithoutPersistOrBroadcast() {
        stubManager(BoardRole.OWNER);
        when(quizSessionRepository.existsByBoardIdAndStatus(BOARD_ID, QuizStatus.ACTIVE)).thenReturn(true);

        service.handle(BOARD_ID, new CanvasActionMessage("quiz:start", validStartPayload()), principal());

        verifyNoStartSideEffects();
    }

    @Test
    void start_viewerCannotManage_droppedWithoutPersistOrBroadcast() {
        stubManager(BoardRole.VIEWER);

        service.handle(BOARD_ID, new CanvasActionMessage("quiz:start", validStartPayload()), principal());

        verify(quizSessionRepository, never()).existsByBoardIdAndStatus(any(), any());
        verifyNoStartSideEffects();
    }

    @Test
    void start_zeroQuestions_droppedWithoutPersistOrBroadcast() {
        stubManager(BoardRole.OWNER);
        when(quizSessionRepository.existsByBoardIdAndStatus(BOARD_ID, QuizStatus.ACTIVE)).thenReturn(false);

        service.handle(BOARD_ID,
                new CanvasActionMessage("quiz:start", Map.of("questions", List.of())), principal());

        verifyNoStartSideEffects();
    }

    @Test
    void start_choiceCountBelowTwo_droppedWithoutPersistOrBroadcast() {
        stubManager(BoardRole.OWNER);
        when(quizSessionRepository.existsByBoardIdAndStatus(BOARD_ID, QuizStatus.ACTIVE)).thenReturn(false);
        Map<String, Object> data = Map.of("questions", List.of(
                Map.of("text", "Q", "choices", List.of(Map.of("text", "A", "correct", true)))));

        service.handle(BOARD_ID, new CanvasActionMessage("quiz:start", data), principal());

        verifyNoStartSideEffects();
    }

    @Test
    void start_noCorrectChoice_droppedWithoutPersistOrBroadcast() {
        stubManager(BoardRole.OWNER);
        when(quizSessionRepository.existsByBoardIdAndStatus(BOARD_ID, QuizStatus.ACTIVE)).thenReturn(false);
        Map<String, Object> data = Map.of("questions", List.of(
                Map.of("text", "Q", "choices", List.of(
                        Map.of("text", "A", "correct", false),
                        Map.of("text", "B", "correct", false)))));

        service.handle(BOARD_ID, new CanvasActionMessage("quiz:start", data), principal());

        verifyNoStartSideEffects();
    }

    @Test
    void start_blankQuestionText_droppedWithoutPersistOrBroadcast() {
        stubManager(BoardRole.OWNER);
        when(quizSessionRepository.existsByBoardIdAndStatus(BOARD_ID, QuizStatus.ACTIVE)).thenReturn(false);
        Map<String, Object> data = Map.of("questions", List.of(
                Map.of("text", "   ", "choices", List.of(
                        Map.of("text", "A", "correct", true),
                        Map.of("text", "B", "correct", false)))));

        service.handle(BOARD_ID, new CanvasActionMessage("quiz:start", data), principal());

        verifyNoStartSideEffects();
    }

    @Test
    void start_lostRaceOnUniqueActiveIndex_droppedSilently() {
        stubManager(BoardRole.OWNER);
        when(quizSessionRepository.existsByBoardIdAndStatus(BOARD_ID, QuizStatus.ACTIVE)).thenReturn(false);
        when(quizSessionRepository.save(any(QuizSession.class)))
                .thenThrow(new DataIntegrityViolationException("uq_quiz_session_active_per_board"));

        service.handle(BOARD_ID, new CanvasActionMessage("quiz:start", validStartPayload()), principal());

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    // =========================================================================
    // quiz:answer
    // =========================================================================

    @Test
    void answer_newAnswer_upsertsAndBroadcastsAnsweredCountOnlyMasked() {
        QuizSession session = openSession(0);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));
        UUID questionId = UUID.randomUUID();
        Question question = question(questionId, SESSION_ID, 0, "2+2?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0)).thenReturn(Optional.of(question));
        UUID choiceId = UUID.randomUUID();
        when(choiceRepository.countByIdAndQuestionId(choiceId, questionId)).thenReturn(1L);
        when(answerRepository.findBySessionIdAndQuestionIdAndUserId(SESSION_ID, questionId, USER_ID))
                .thenReturn(Optional.empty());
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(questionId))).thenReturn(List.of(
                choice(choiceId, questionId, 0, "4", true),
                choice(UUID.randomUUID(), questionId, 1, "5", false)));
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, questionId)).thenReturn(1L);
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(question));

        Map<String, Object> data = Map.of(
                "sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString(),
                "questionId", questionId.toString(), "choiceId", choiceId.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:answer", data), principal());

        ArgumentCaptor<Answer> answerCaptor = ArgumentCaptor.forClass(Answer.class);
        verify(answerRepository).save(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getChoiceId()).isEqualTo(choiceId);
        assertThat(answerCaptor.getValue().getUserId()).isEqualTo(USER_ID);

        ArgumentCaptor<BroadcastCanvasMessage> captor = ArgumentCaptor.forClass(BroadcastCanvasMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/whiteboard/" + BOARD_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("quiz:updated");
        Map<String, Object> payload = map(captor.getValue());
        assertNoCorrectKeyAnywhere(payload);
        Map<String, Object> currentQuestion = asMap(payload.get("currentQuestion"));
        assertThat(((Number) currentQuestion.get("answeredCount")).intValue()).isEqualTo(1);
    }

    @Test
    void answer_existingAnswer_replacesChoiceInPlaceRatherThanInsertingADuplicate() {
        QuizSession session = openSession(0);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));
        UUID questionId = UUID.randomUUID();
        Question question = question(questionId, SESSION_ID, 0, "2+2?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0)).thenReturn(Optional.of(question));
        UUID newChoiceId = UUID.randomUUID();
        when(choiceRepository.countByIdAndQuestionId(newChoiceId, questionId)).thenReturn(1L);

        UUID existingAnswerId = UUID.randomUUID();
        Answer existing = new Answer(SESSION_ID, questionId, UUID.randomUUID(), USER_ID, Instant.now());
        setId(existing, existingAnswerId);
        when(answerRepository.findBySessionIdAndQuestionIdAndUserId(SESSION_ID, questionId, USER_ID))
                .thenReturn(Optional.of(existing));
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(questionId))).thenReturn(List.of());
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, questionId)).thenReturn(1L);
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(question));

        Map<String, Object> data = Map.of(
                "sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString(),
                "questionId", questionId.toString(), "choiceId", newChoiceId.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:answer", data), principal());

        ArgumentCaptor<Answer> captor = ArgumentCaptor.forClass(Answer.class);
        verify(answerRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existingAnswerId);
        assertThat(captor.getValue().getChoiceId()).isEqualTo(newChoiceId);
    }

    @Test
    void answer_choiceDoesNotBelongToCurrentQuestion_droppedWithoutPersistOrBroadcast() {
        QuizSession session = openSession(0);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));
        UUID questionId = UUID.randomUUID();
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0))
                .thenReturn(Optional.of(question(questionId, SESSION_ID, 0, "2+2?")));
        UUID foreignChoiceId = UUID.randomUUID();
        when(choiceRepository.countByIdAndQuestionId(foreignChoiceId, questionId)).thenReturn(0L);

        Map<String, Object> data = Map.of(
                "sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString(),
                "questionId", questionId.toString(), "choiceId", foreignChoiceId.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:answer", data), principal());

        verify(answerRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void answer_questionAlreadyRevealed_droppedWithoutPersistOrBroadcast() {
        QuizSession session = openSession(0);
        session.setCurrentState(QuestionState.REVEALED);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));

        Map<String, Object> data = Map.of(
                "sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString(),
                "questionId", UUID.randomUUID().toString(), "choiceId", UUID.randomUUID().toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:answer", data), principal());

        verify(answerRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
        verify(questionRepository, never()).findBySessionIdAndPosition(any(), anyInt());
    }

    @Test
    void answer_quizNotStartedYet_droppedWithoutPersistOrBroadcast() {
        QuizSession session = openSession(0);
        session.setCurrentQuestionIndex(null);
        session.setCurrentState(null);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));

        Map<String, Object> data = Map.of(
                "sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString(),
                "questionId", UUID.randomUUID().toString(), "choiceId", UUID.randomUUID().toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:answer", data), principal());

        verify(answerRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void answer_staleQuestionId_droppedWithoutPersistOrBroadcast() {
        QuizSession session = openSession(0);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));
        UUID actualCurrentQuestionId = UUID.randomUUID();
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0))
                .thenReturn(Optional.of(question(actualCurrentQuestionId, SESSION_ID, 0, "2+2?")));

        UUID staleQuestionId = UUID.randomUUID();
        Map<String, Object> data = Map.of(
                "sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString(),
                "questionId", staleQuestionId.toString(), "choiceId", UUID.randomUUID().toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:answer", data), principal());

        verify(answerRepository, never()).save(any());
        verify(choiceRepository, never()).countByIdAndQuestionId(any(), any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    // =========================================================================
    // quiz:next
    // =========================================================================

    @Test
    void next_managerAdvancesToNextQuestion_broadcastsMaskedUpdate() {
        stubManager(BoardRole.OWNER);
        QuizSession session = openSession(0);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));
        UUID nextQuestionId = UUID.randomUUID();
        Question nextQuestion = question(nextQuestionId, SESSION_ID, 1, "3+3?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 1)).thenReturn(Optional.of(nextQuestion));
        when(quizSessionRepository.save(any(QuizSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(nextQuestion));
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(nextQuestionId))).thenReturn(List.of());
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, nextQuestionId)).thenReturn(0L);

        Map<String, Object> data = Map.of("sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:next", data), principal());

        assertThat(session.getCurrentQuestionIndex()).isEqualTo(1);
        assertThat(session.getCurrentState()).isEqualTo(QuestionState.OPEN);
        ArgumentCaptor<BroadcastCanvasMessage> captor = ArgumentCaptor.forClass(BroadcastCanvasMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/whiteboard/" + BOARD_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("quiz:updated");
        assertNoCorrectKeyAnywhere(map(captor.getValue()));
    }

    @Test
    void next_noFurtherQuestion_noOpDecisionD3() {
        stubManager(BoardRole.OWNER);
        QuizSession session = openSession(0);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 1)).thenReturn(Optional.empty());

        Map<String, Object> data = Map.of("sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:next", data), principal());

        assertThat(session.getCurrentQuestionIndex()).isEqualTo(0);
        verify(quizSessionRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void next_viewerCannotManage_droppedWithoutLockOrBroadcast() {
        stubManager(BoardRole.VIEWER);

        Map<String, Object> data = Map.of("sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:next", data), principal());

        verify(quizSessionRepository, never()).findForUpdate(any(), any(), any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    // =========================================================================
    // quiz:reveal
    // =========================================================================

    @Test
    void reveal_managerRevealsOpenQuestion_broadcastsDemaskedDistributionAndCorrectFlags() {
        stubManager(BoardRole.OWNER);
        QuizSession session = openSession(0);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));
        when(quizSessionRepository.save(any(QuizSession.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID questionId = UUID.randomUUID();
        Question question = question(questionId, SESSION_ID, 0, "2+2?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0)).thenReturn(Optional.of(question));
        UUID correctChoiceId = UUID.randomUUID();
        Choice correctChoice = choice(correctChoiceId, questionId, 0, "4", true);
        Choice wrongChoice = choice(UUID.randomUUID(), questionId, 1, "5", false);
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(questionId)))
                .thenReturn(List.of(correctChoice, wrongChoice));
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, questionId)).thenReturn(1L);
        Answer answer = new Answer(SESSION_ID, questionId, correctChoiceId, USER_ID, Instant.now());
        when(answerRepository.findAllBySessionId(SESSION_ID)).thenReturn(List.of(answer));
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(question));

        Map<String, Object> data = Map.of("sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:reveal", data), principal());

        assertThat(session.getCurrentState()).isEqualTo(QuestionState.REVEALED);
        ArgumentCaptor<BroadcastCanvasMessage> captor = ArgumentCaptor.forClass(BroadcastCanvasMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/whiteboard/" + BOARD_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("quiz:updated");
        Map<String, Object> payload = map(captor.getValue());
        Map<String, Object> currentQuestion = asMap(payload.get("currentQuestion"));
        assertThat(currentQuestion.get("state")).isEqualTo("REVEALED");
        List<Map<String, Object>> choices = asMapList(currentQuestion.get("choices"));
        assertThat(choices).hasSize(2);
        assertThat(choices.get(0)).containsEntry("correct", true);
        assertThat(((Number) choices.get(0).get("count")).intValue()).isEqualTo(1);
        assertThat(choices.get(1)).containsEntry("correct", false);
        assertThat(((Number) choices.get(1).get("count")).intValue()).isZero();
    }

    @Test
    void reveal_viewerCannotManage_droppedWithoutLockOrBroadcast() {
        stubManager(BoardRole.VIEWER);

        Map<String, Object> data = Map.of("sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:reveal", data), principal());

        verify(quizSessionRepository, never()).findForUpdate(any(), any(), any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    void reveal_alreadyRevealed_droppedNoOp() {
        stubManager(BoardRole.OWNER);
        QuizSession session = openSession(0);
        session.setCurrentState(QuestionState.REVEALED);
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));

        Map<String, Object> data = Map.of("sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:reveal", data), principal());

        verify(quizSessionRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    // =========================================================================
    // quiz:stop
    // =========================================================================

    @Test
    void stop_managerClosesSession_broadcastsFullyDemaskedFinalLeaderboardEvenWithoutPriorReveal() {
        stubManager(BoardRole.OWNER);
        QuizSession session = openSession(0); // still OPEN, never explicitly revealed
        when(quizSessionRepository.findForUpdate(SESSION_ID, BOARD_ID, TENANT_ID)).thenReturn(Optional.of(session));
        when(quizSessionRepository.save(any(QuizSession.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID questionId = UUID.randomUUID();
        Question question = question(questionId, SESSION_ID, 0, "2+2?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0)).thenReturn(Optional.of(question));
        UUID correctChoiceId = UUID.randomUUID();
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(questionId)))
                .thenReturn(List.of(choice(correctChoiceId, questionId, 0, "4", true)));
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, questionId)).thenReturn(0L);
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(question));
        when(answerRepository.findAllBySessionId(SESSION_ID)).thenReturn(List.of());

        Map<String, Object> data = Map.of("sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:stop", data), principal());

        assertThat(session.getStatus()).isEqualTo(QuizStatus.CLOSED);
        assertThat(session.getClosedAt()).isNotNull();
        ArgumentCaptor<BroadcastCanvasMessage> captor = ArgumentCaptor.forClass(BroadcastCanvasMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/whiteboard/" + BOARD_ID), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("quiz:session:closed");
        Map<String, Object> payload = map(captor.getValue());
        assertThat(payload.get("status")).isEqualTo("CLOSED");
        Map<String, Object> currentQuestion = asMap(payload.get("currentQuestion"));
        List<Map<String, Object>> choices = asMapList(currentQuestion.get("choices"));
        // Demasked even though the question was still OPEN (never explicitly revealed) at stop time.
        assertThat(choices.get(0)).containsEntry("correct", true);
    }

    @Test
    void stop_viewerCannotManage_droppedWithoutLockOrBroadcast() {
        stubManager(BoardRole.VIEWER);

        Map<String, Object> data = Map.of("sessionId", SESSION_ID.toString(), "boardId", BOARD_ID.toString());
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:stop", data), principal());

        verify(quizSessionRepository, never()).findForUpdate(any(), any(), any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    // =========================================================================
    // Dispatch
    // =========================================================================

    @Test
    void handle_unknownActionType_droppedWithoutSideEffects() {
        service.handle(BOARD_ID, new CanvasActionMessage("quiz:bogus", Map.of()), principal());

        verifyNoInteractions(quizSessionRepository, questionRepository, choiceRepository,
                answerRepository, messagingTemplate);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void verifyNoStartSideEffects() {
        verify(quizSessionRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }

    private void stubManager(final BoardRole role) {
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(BOARD_ID, USER_ID))
                .thenReturn(Optional.of(new BoardMember(new BoardMemberId(BOARD_ID, USER_ID), role, Instant.now())));
    }

    private static StompPrincipal principal() {
        return new StompPrincipal(USER_ID, TENANT_ID);
    }

    private static Map<String, Object> validStartPayload() {
        return Map.of("questions", List.of(
                Map.of("text", "2+2?", "choices", List.of(
                        Map.of("text", "3", "correct", false),
                        Map.of("text", "4", "correct", true)))));
    }

    private QuizSession openSession(final int index) {
        QuizSession s = new QuizSession(BOARD_ID, TENANT_ID, Instant.now());
        setId(s, SESSION_ID);
        s.setStatus(QuizStatus.ACTIVE);
        s.setCurrentQuestionIndex(index);
        s.setCurrentState(QuestionState.OPEN);
        return s;
    }

    private static Question question(final UUID id, final UUID sessionId, final int position, final String text) {
        Question q = new Question(sessionId, position, text, null);
        setId(q, id);
        return q;
    }

    private static Choice choice(
            final UUID id, final UUID questionId, final int position, final String text, final boolean correct) {
        Choice c = new Choice(questionId, position, text, correct);
        setId(c, id);
        return c;
    }

    private static void setId(final Object entity, final UUID id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(final Object value) {
        return (List<Map<String, Object>>) value;
    }

    /**
     * Recursively asserts that {@code node} — and every nested map/list reachable from it —
     * never carries a {@code "correct"} key. This is the critical masking assertion (§2.4/§9 of
     * {@code QUIZ-ACTIVITY-DESIGN.md}): it inspects the exact JSON-shaped structure the frontend
     * would receive over the wire (via {@code ObjectMapper#convertValue}), not just the top-level
     * or the one field we happen to think of — a future refactor that accidentally starts
     * serialising {@code ChoiceRevealResponse} while a question is still {@code OPEN} would fail
     * this check regardless of where in the payload it appears.
     *
     * @param node the (sub-)value to inspect, typically a {@code Map<String, Object>} broadcast
     *             payload
     */
    private static void assertNoCorrectKeyAnywhere(final Object node) {
        if (node instanceof Map<?, ?> map) {
            assertThat(map.containsKey("correct"))
                    .as("broadcast payload must never carry a 'correct' key before reveal")
                    .isFalse();
            map.values().forEach(QuizActionServiceTest::assertNoCorrectKeyAnywhere);
        } else if (node instanceof List<?> list) {
            list.forEach(QuizActionServiceTest::assertNoCorrectKeyAnywhere);
        }
    }
}
