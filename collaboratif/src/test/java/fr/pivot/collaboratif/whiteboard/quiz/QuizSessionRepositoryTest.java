package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.AbstractCollaboratifIntegrationTest;
import fr.pivot.collaboratif.testsupport.PlatformAuthTestSupport;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link QuizSessionRepository} (plus the {@code quiz_answer} unique
 * constraint) against a real PostgreSQL via Testcontainers ({@link
 * AbstractCollaboratifIntegrationTest}'s module-wide singleton container). Verifies the two
 * database-level guarantees {@link QuizActionService} relies on that cannot be exercised with
 * mocks (Lot D1, whiteboard quiz activity, complementing {@link QuizActionServiceTest}'s
 * plain-Mockito coverage of the service logic itself):
 * <ul>
 *   <li>{@link QuizSessionRepository#findForUpdate} scoping by {@code (id, boardId, tenantId)} —
 *       the anti-IDOR/anti-race primitive every mutation handler (
 *       {@code answer}/{@code next}/{@code reveal}/{@code stop}) relies on.</li>
 *   <li>{@code uq_quiz_session_active_per_board} — at most one {@link QuizStatus#ACTIVE} session
 *       per board ({@code V9__quiz.sql}), the invariant {@code quiz:start} races against.</li>
 *   <li>{@code uq_quiz_answer_once} — at most one {@link Answer} per
 *       {@code (session, question, user)} ({@code V9__quiz.sql}), the invariant the
 *       {@code quiz:answer} upsert relies on.</li>
 * </ul>
 */
@SpringBootTest
class QuizSessionRepositoryTest extends AbstractCollaboratifIntegrationTest {

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private QuizSessionRepository quizSessionRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private ChoiceRepository choiceRepository;

    @Autowired
    private AnswerRepository answerRepository;

    // =========================================================================
    // findForUpdate — scoped by (id, boardId, tenantId)
    // =========================================================================

    @Test
    @Transactional
    void findForUpdate_matchingIdBoardAndTenant_returnsTheSession() throws Exception {
        long tenantId = seedTenant();
        Board board = seedBoard(tenantId);
        QuizSession session = quizSessionRepository.save(new QuizSession(board.getId(), tenantId, Instant.now()));

        Optional<QuizSession> found = quizSessionRepository.findForUpdate(session.getId(), board.getId(), tenantId);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(session.getId());
    }

    @Test
    @Transactional
    void findForUpdate_wrongBoardId_returnsEmpty() throws Exception {
        long tenantId = seedTenant();
        Board board = seedBoard(tenantId);
        QuizSession session = quizSessionRepository.save(new QuizSession(board.getId(), tenantId, Instant.now()));

        Optional<QuizSession> found =
                quizSessionRepository.findForUpdate(session.getId(), UUID.randomUUID(), tenantId);

        assertThat(found).isEmpty();
    }

    @Test
    @Transactional
    void findForUpdate_wrongTenantId_returnsEmptyEvenForTheCorrectBoard() throws Exception {
        long tenantId = seedTenant();
        long otherTenantId = seedTenant();
        Board board = seedBoard(tenantId);
        QuizSession session = quizSessionRepository.save(new QuizSession(board.getId(), tenantId, Instant.now()));

        Optional<QuizSession> found =
                quizSessionRepository.findForUpdate(session.getId(), board.getId(), otherTenantId);

        assertThat(found).isEmpty();
    }

    @Test
    @Transactional
    void findForUpdate_unknownId_returnsEmpty() throws Exception {
        long tenantId = seedTenant();
        Board board = seedBoard(tenantId);

        Optional<QuizSession> found =
                quizSessionRepository.findForUpdate(UUID.randomUUID(), board.getId(), tenantId);

        assertThat(found).isEmpty();
    }

    // =========================================================================
    // uq_quiz_session_active_per_board — single-ACTIVE-session-per-board invariant
    // =========================================================================

    @Test
    void secondActiveSessionOnSameBoard_violatesPartialUniqueIndex() throws Exception {
        long tenantId = seedTenant();
        Board board = seedBoard(tenantId);
        quizSessionRepository.saveAndFlush(new QuizSession(board.getId(), tenantId, Instant.now()));

        QuizSession second = new QuizSession(board.getId(), tenantId, Instant.now());

        assertThatThrownBy(() -> quizSessionRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void closedSessionDoesNotBlockStartingANewActiveSession() throws Exception {
        long tenantId = seedTenant();
        Board board = seedBoard(tenantId);
        QuizSession closed =
                quizSessionRepository.saveAndFlush(new QuizSession(board.getId(), tenantId, Instant.now()));
        closed.setStatus(QuizStatus.CLOSED);
        closed.setClosedAt(Instant.now());
        quizSessionRepository.saveAndFlush(closed);

        QuizSession secondActive =
                quizSessionRepository.saveAndFlush(new QuizSession(board.getId(), tenantId, Instant.now()));

        assertThat(secondActive.getId()).isNotEqualTo(closed.getId());
        assertThat(quizSessionRepository.existsByBoardIdAndStatus(board.getId(), QuizStatus.ACTIVE)).isTrue();
    }

    // =========================================================================
    // uq_quiz_answer_once — one answer per (session, question, user)
    // =========================================================================

    @Test
    void secondAnswerForSameSessionQuestionUser_violatesUniqueConstraint() throws Exception {
        long tenantId = seedTenant();
        long userId = seedUser(tenantId);
        Board board = seedBoard(tenantId);
        QuizSession session =
                quizSessionRepository.saveAndFlush(new QuizSession(board.getId(), tenantId, Instant.now()));
        Question question = questionRepository.saveAndFlush(new Question(session.getId(), 0, "2+2?", null));
        Choice choiceA = choiceRepository.saveAndFlush(new Choice(question.getId(), 0, "4", true));
        Choice choiceB = choiceRepository.saveAndFlush(new Choice(question.getId(), 1, "5", false));

        answerRepository.saveAndFlush(
                new Answer(session.getId(), question.getId(), choiceA.getId(), userId, Instant.now()));
        Answer duplicate = new Answer(session.getId(), question.getId(), choiceB.getId(), userId, Instant.now());

        assertThatThrownBy(() -> answerRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameUserAnsweringDifferentQuestions_isAllowed() throws Exception {
        long tenantId = seedTenant();
        long userId = seedUser(tenantId);
        Board board = seedBoard(tenantId);
        QuizSession session =
                quizSessionRepository.saveAndFlush(new QuizSession(board.getId(), tenantId, Instant.now()));
        Question q0 = questionRepository.saveAndFlush(new Question(session.getId(), 0, "Q0", null));
        Question q1 = questionRepository.saveAndFlush(new Question(session.getId(), 1, "Q1", null));
        Choice choice0 = choiceRepository.saveAndFlush(new Choice(q0.getId(), 0, "A", true));
        Choice choice1 = choiceRepository.saveAndFlush(new Choice(q1.getId(), 0, "A", true));

        answerRepository.saveAndFlush(new Answer(session.getId(), q0.getId(), choice0.getId(), userId, Instant.now()));
        answerRepository.saveAndFlush(new Answer(session.getId(), q1.getId(), choice1.getId(), userId, Instant.now()));

        assertThat(answerRepository.findAllBySessionId(session.getId())).hasSize(2);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Board seedBoard(final long tenantId) throws Exception {
        long ownerId = seedUser(tenantId);
        return boardRepository.save(new Board("Quiz repository test board", tenantId, ownerId, Instant.now()));
    }

    private long seedTenant() throws Exception {
        return PlatformAuthTestSupport.seedTenant(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), null);
    }

    private long seedUser(final long tenantId) throws Exception {
        return PlatformAuthTestSupport.seedUser(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), tenantId, true);
    }
}
