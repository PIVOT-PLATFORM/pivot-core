package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMember;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberId;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.quiz.dto.ChoiceResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.ChoiceRevealResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.LeaderboardEntryResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuizSessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
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
 * Unit tests for {@link QuizQueryService} (Lot D1, whiteboard quiz activity) — plain-Mockito unit
 * tests (no Testcontainers needed: every repository is mocked), mirroring
 * {@code RetroVoteServiceTest}'s style.
 *
 * <p><strong>AC → test mapping (§2.3, §7.3 of {@code QUIZ-ACTIVITY-DESIGN.md}):</strong>
 * <ul>
 *   <li>Anti-IDOR, cross-tenant/unknown board → 404 →
 *       {@link #current_crossTenantOrUnknownBoard_throwsBoardNotFoundException()}</li>
 *   <li>Anti-IDOR, same-tenant non-member → 404 →
 *       {@link #current_nonMemberOfExistingBoard_throwsBoardNotFoundException()}</li>
 *   <li>Access granted paths → {@code current_ownerNotExplicitMember_*}/{@code current_memberButNotOwner_*}</li>
 *   <li>US-Q4 rehydration, masking per state → {@code current_activeSession*}</li>
 *   <li>US-Q4 last/leaderboard → {@code last_*}</li>
 *   <li>Leaderboard never leaks the still-open current question →
 *       {@link #current_leaderboardExcludesTheStillOpenCurrentQuestion_andTiesShareRank()}</li>
 * </ul>
 */
class QuizQueryServiceTest {

    private static final UUID BOARD_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final Long TENANT_ID = 100L;
    private static final Long OWNER_ID = 1L;
    private static final Long MEMBER_ID = 2L;
    private static final Long STRANGER_ID = 3L;

    private BoardRepository boardRepository;
    private BoardMemberRepository boardMemberRepository;
    private QuizSessionRepository quizSessionRepository;
    private QuestionRepository questionRepository;
    private ChoiceRepository choiceRepository;
    private AnswerRepository answerRepository;
    private QuizQueryService service;

    @BeforeEach
    void setUp() {
        boardRepository = mock(BoardRepository.class);
        boardMemberRepository = mock(BoardMemberRepository.class);
        quizSessionRepository = mock(QuizSessionRepository.class);
        questionRepository = mock(QuestionRepository.class);
        choiceRepository = mock(ChoiceRepository.class);
        answerRepository = mock(AnswerRepository.class);
        service = new QuizQueryService(
                boardRepository, boardMemberRepository, quizSessionRepository,
                questionRepository, choiceRepository, answerRepository);
    }

    // =========================================================================
    // Anti-IDOR (requireBoardAccess)
    // =========================================================================

    @Test
    void current_crossTenantOrUnknownBoard_throwsBoardNotFoundException() {
        // A cross-tenant boardId simply never resolves through the tenant-scoped lookup —
        // exactly what happens for an unknown board too (single exception, no disclosure).
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(BOARD_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.current(BOARD_ID, STRANGER_ID, TENANT_ID))
                .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    void current_nonMemberOfExistingBoard_throwsBoardNotFoundException() {
        Board board = board(BOARD_ID, OWNER_ID);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(BOARD_ID, TENANT_ID))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(BOARD_ID, STRANGER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.current(BOARD_ID, STRANGER_ID, TENANT_ID))
                .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    void current_ownerNotExplicitMemberRow_accessGranted() {
        Board board = board(BOARD_ID, OWNER_ID);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(BOARD_ID, TENANT_ID))
                .thenReturn(Optional.of(board));
        when(quizSessionRepository.findByBoardIdAndTenantIdAndStatus(BOARD_ID, TENANT_ID, QuizStatus.ACTIVE))
                .thenReturn(Optional.empty());

        QuizSessionResponse response = service.current(BOARD_ID, OWNER_ID, TENANT_ID);

        assertThat(response).isNull();
        verify(boardMemberRepository, never()).findByIdBoardIdAndIdUserId(any(), any());
    }

    @Test
    void current_memberButNotOwner_accessGranted() {
        Board board = board(BOARD_ID, OWNER_ID);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(BOARD_ID, TENANT_ID))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(BOARD_ID, MEMBER_ID))
                .thenReturn(Optional.of(new BoardMember(
                        new BoardMemberId(BOARD_ID, MEMBER_ID), BoardRole.VIEWER, Instant.now())));
        when(quizSessionRepository.findByBoardIdAndTenantIdAndStatus(BOARD_ID, TENANT_ID, QuizStatus.ACTIVE))
                .thenReturn(Optional.empty());

        QuizSessionResponse response = service.current(BOARD_ID, MEMBER_ID, TENANT_ID);

        assertThat(response).isNull();
    }

    // =========================================================================
    // current() — masking per state
    // =========================================================================

    @Test
    void current_noActiveSession_returnsNull() {
        stubBoardAccessGranted();
        when(quizSessionRepository.findByBoardIdAndTenantIdAndStatus(BOARD_ID, TENANT_ID, QuizStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(service.current(BOARD_ID, MEMBER_ID, TENANT_ID)).isNull();
    }

    @Test
    void current_activeSessionNotYetStarted_currentQuestionNullAndLeaderboardEmpty() {
        stubBoardAccessGranted();
        QuizSession session = session(QuizStatus.ACTIVE, null, null);
        when(quizSessionRepository.findByBoardIdAndTenantIdAndStatus(BOARD_ID, TENANT_ID, QuizStatus.ACTIVE))
                .thenReturn(Optional.of(session));

        QuizSessionResponse response = service.current(BOARD_ID, MEMBER_ID, TENANT_ID);

        assertThat(response).isNotNull();
        assertThat(response.currentQuestion()).isNull();
        assertThat(response.leaderboard()).isEmpty();
        verify(questionRepository, never()).findAllBySessionIdOrderByPositionAsc(any());
    }

    @Test
    void current_openQuestion_choicesAreMaskedWithoutCorrectFlag() {
        stubBoardAccessGranted();
        QuizSession session = session(QuizStatus.ACTIVE, 0, QuestionState.OPEN);
        when(quizSessionRepository.findByBoardIdAndTenantIdAndStatus(BOARD_ID, TENANT_ID, QuizStatus.ACTIVE))
                .thenReturn(Optional.of(session));
        UUID questionId = UUID.randomUUID();
        Question question = question(questionId, SESSION_ID, 0, "2+2?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0)).thenReturn(Optional.of(question));
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(questionId)))
                .thenReturn(List.of(choice(UUID.randomUUID(), questionId, 0, "4", true)));
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, questionId)).thenReturn(0L);
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(question));

        QuizSessionResponse response = service.current(BOARD_ID, MEMBER_ID, TENANT_ID);

        assertThat(response.currentQuestion().state()).isEqualTo("OPEN");
        assertThat(response.currentQuestion().choices())
                .hasSize(1)
                .allSatisfy(c -> assertThat(c)
                        .isInstanceOf(ChoiceResponse.class)
                        .isNotInstanceOf(ChoiceRevealResponse.class));
    }

    @Test
    void current_revealedQuestion_choicesAreDemaskedWithCorrectAndCount() {
        stubBoardAccessGranted();
        QuizSession session = session(QuizStatus.ACTIVE, 0, QuestionState.REVEALED);
        when(quizSessionRepository.findByBoardIdAndTenantIdAndStatus(BOARD_ID, TENANT_ID, QuizStatus.ACTIVE))
                .thenReturn(Optional.of(session));
        UUID questionId = UUID.randomUUID();
        Question question = question(questionId, SESSION_ID, 0, "2+2?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0)).thenReturn(Optional.of(question));
        UUID choiceId = UUID.randomUUID();
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(questionId)))
                .thenReturn(List.of(choice(choiceId, questionId, 0, "4", true)));
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, questionId)).thenReturn(1L);
        when(answerRepository.findAllBySessionId(SESSION_ID)).thenReturn(
                List.of(new Answer(SESSION_ID, questionId, choiceId, MEMBER_ID, Instant.now())));
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(question));

        QuizSessionResponse response = service.current(BOARD_ID, MEMBER_ID, TENANT_ID);

        assertThat(response.currentQuestion().state()).isEqualTo("REVEALED");
        assertThat(response.currentQuestion().choices()).hasSize(1);
        ChoiceRevealResponse revealed = (ChoiceRevealResponse) response.currentQuestion().choices().get(0);
        assertThat(revealed.correct()).isTrue();
        assertThat(revealed.count()).isEqualTo(1);
    }

    // =========================================================================
    // last()
    // =========================================================================

    @Test
    void last_noClosedSession_returnsNull() {
        stubBoardAccessGranted();
        when(quizSessionRepository.findFirstByBoardIdAndTenantIdAndStatusOrderByCreatedAtDesc(
                BOARD_ID, TENANT_ID, QuizStatus.CLOSED)).thenReturn(Optional.empty());

        assertThat(service.last(BOARD_ID, MEMBER_ID, TENANT_ID)).isNull();
    }

    @Test
    void last_boardNotFound_throwsBoardNotFoundException() {
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(BOARD_ID, TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.last(BOARD_ID, STRANGER_ID, TENANT_ID))
                .isInstanceOf(BoardNotFoundException.class);
    }

    @Test
    void last_closedSession_fullyDemaskedRegardlessOfWhetherLastQuestionWasEverRevealed() {
        stubBoardAccessGranted();
        QuizSession session = session(QuizStatus.CLOSED, 0, QuestionState.OPEN); // never revealed before stop
        when(quizSessionRepository.findFirstByBoardIdAndTenantIdAndStatusOrderByCreatedAtDesc(
                BOARD_ID, TENANT_ID, QuizStatus.CLOSED)).thenReturn(Optional.of(session));
        UUID questionId = UUID.randomUUID();
        Question question = question(questionId, SESSION_ID, 0, "2+2?");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 0)).thenReturn(Optional.of(question));
        UUID choiceId = UUID.randomUUID();
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(questionId)))
                .thenReturn(List.of(choice(choiceId, questionId, 0, "4", true)));
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, questionId)).thenReturn(0L);
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(question));
        when(answerRepository.findAllBySessionId(SESSION_ID)).thenReturn(List.of());

        QuizSessionResponse response = service.last(BOARD_ID, MEMBER_ID, TENANT_ID);

        assertThat(response.status()).isEqualTo("CLOSED");
        assertThat(response.currentQuestion().state()).isEqualTo("REVEALED");
        ChoiceRevealResponse revealed = (ChoiceRevealResponse) response.currentQuestion().choices().get(0);
        assertThat(revealed.correct()).isTrue();
    }

    // =========================================================================
    // Leaderboard
    // =========================================================================

    @Test
    void current_leaderboardExcludesTheStillOpenCurrentQuestion_andTiesShareRank() {
        stubBoardAccessGranted();
        // The session is on question index 1 (still OPEN) — the leaderboard must count only
        // question 0 (fully passed), never leak anything about the still-open question 1.
        QuizSession session = session(QuizStatus.ACTIVE, 1, QuestionState.OPEN);
        when(quizSessionRepository.findByBoardIdAndTenantIdAndStatus(BOARD_ID, TENANT_ID, QuizStatus.ACTIVE))
                .thenReturn(Optional.of(session));

        UUID q0Id = UUID.randomUUID();
        UUID q1Id = UUID.randomUUID();
        Question q0 = question(q0Id, SESSION_ID, 0, "Q0");
        Question q1 = question(q1Id, SESSION_ID, 1, "Q1");
        when(questionRepository.findBySessionIdAndPosition(SESSION_ID, 1)).thenReturn(Optional.of(q1));
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(q1Id))).thenReturn(List.of());
        when(answerRepository.countBySessionIdAndQuestionId(SESSION_ID, q1Id)).thenReturn(0L);
        when(questionRepository.findAllBySessionIdOrderByPositionAsc(SESSION_ID)).thenReturn(List.of(q0, q1));

        UUID q0CorrectChoiceId = UUID.randomUUID();
        UUID q0WrongChoiceId = UUID.randomUUID();
        when(choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(q0Id))).thenReturn(List.of(
                choice(q0CorrectChoiceId, q0Id, 0, "right", true),
                choice(q0WrongChoiceId, q0Id, 1, "wrong", false)));

        Instant now = Instant.now();
        // userA and userB both answered q0 correctly (tie); userC answered q0 wrong (absent from
        // the leaderboard entirely — the code only tracks positive scorers). A fourth answer on
        // the still-open q1 must not influence the leaderboard at all.
        when(answerRepository.findAllBySessionId(SESSION_ID)).thenReturn(List.of(
                new Answer(SESSION_ID, q0Id, q0CorrectChoiceId, 11L, now),
                new Answer(SESSION_ID, q0Id, q0CorrectChoiceId, 12L, now),
                new Answer(SESSION_ID, q0Id, q0WrongChoiceId, 13L, now),
                new Answer(SESSION_ID, q1Id, q0CorrectChoiceId, 11L, now)));

        List<LeaderboardEntryResponse> leaderboard = service.current(BOARD_ID, MEMBER_ID, TENANT_ID).leaderboard();

        assertThat(leaderboard).hasSize(2);
        assertThat(leaderboard).extracting(LeaderboardEntryResponse::userId)
                .containsExactlyInAnyOrder("11", "12");
        assertThat(leaderboard).allSatisfy(entry -> {
            assertThat(entry.score()).isEqualTo(1);
            assertThat(entry.rank()).isEqualTo(1);
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void stubBoardAccessGranted() {
        Board board = board(BOARD_ID, OWNER_ID);
        when(boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(BOARD_ID, TENANT_ID))
                .thenReturn(Optional.of(board));
        when(boardMemberRepository.findByIdBoardIdAndIdUserId(BOARD_ID, MEMBER_ID))
                .thenReturn(Optional.of(new BoardMember(
                        new BoardMemberId(BOARD_ID, MEMBER_ID), BoardRole.VIEWER, Instant.now())));
    }

    private static Board board(final UUID id, final Long ownerId) {
        Board b = new Board("Quiz board", TENANT_ID, ownerId, Instant.now());
        setId(b, id);
        return b;
    }

    private static QuizSession session(
            final QuizStatus status, final Integer index, final QuestionState state) {
        QuizSession s = new QuizSession(BOARD_ID, TENANT_ID, Instant.now());
        setId(s, SESSION_ID);
        s.setStatus(status);
        s.setCurrentQuestionIndex(index);
        s.setCurrentState(state);
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
}
