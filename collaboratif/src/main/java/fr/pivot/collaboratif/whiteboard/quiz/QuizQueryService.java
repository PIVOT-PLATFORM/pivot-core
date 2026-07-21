package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuizSessionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-side service backing the quiz REST endpoints ({@code GET .../quiz/current|last}) — calques
 * {@code VoteQueryService} (Vote feature, the model this quiz module mirrors).
 *
 * <p>Every read first resolves the board within the caller's tenant and asserts the caller is a
 * member — a cross-tenant or non-member board resolves to a {@link BoardNotFoundException} (HTTP
 * 404), never a 403, so the endpoint never confirms the existence of a resource the caller may not
 * see (module anti-IDOR rule). Tenant and user identity come exclusively from the resolved
 * principal, never from a request parameter.
 *
 * <p>⚠️ Masking (decision D4, §2.4/§11 of {@code QUIZ-ACTIVITY-DESIGN.md}): the server never
 * returns a choice's {@code correct} flag, nor the per-choice distribution, for the currently
 * in-play question while it is not yet {@link QuestionState#REVEALED} — <strong>even to an
 * OWNER/EDITOR</strong>. There is deliberately no facilitator-only demasking branch; the
 * facilitator's local composition copy is the only way they see correct answers before they
 * themselves reveal. The cumulative leaderboard, in contrast, only ever aggregates questions that
 * are no longer at risk of leaking the current question's correct choice: fully passed questions
 * (position strictly before the current one) plus the current question once it has actually been
 * revealed — never the still-open current question.
 *
 * <p>Session/question/leaderboard construction is delegated to {@link QuizResponseAssembler}, the
 * package-private assembler also used by {@code QuizActionService} (lot C1, mutation/broadcast
 * side) — the single shared source of truth for masking and scoring logic, guaranteeing a client
 * sees identical results whether it rehydrates over REST or receives a broadcast.
 */
@Service
@Transactional(readOnly = true)
public class QuizQueryService {

    private final BoardRepository boardRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final QuizSessionRepository quizSessionRepository;
    private final QuestionRepository questionRepository;
    private final ChoiceRepository choiceRepository;
    private final AnswerRepository answerRepository;

    /**
     * Creates the service.
     *
     * @param boardRepository       repository used to resolve the board within the caller's tenant
     * @param boardMemberRepository repository used to assert the caller's board membership
     * @param quizSessionRepository repository for {@link QuizSession} lookups
     * @param questionRepository    repository for {@link Question} lookups
     * @param choiceRepository      repository for {@link Choice} lookups
     * @param answerRepository      repository for {@link Answer} lookups (tally/leaderboard source)
     */
    public QuizQueryService(
            final BoardRepository boardRepository,
            final BoardMemberRepository boardMemberRepository,
            final QuizSessionRepository quizSessionRepository,
            final QuestionRepository questionRepository,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository) {
        this.boardRepository = boardRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.quizSessionRepository = quizSessionRepository;
        this.questionRepository = questionRepository;
        this.choiceRepository = choiceRepository;
        this.answerRepository = answerRepository;
    }

    /**
     * Returns the board's currently active quiz session, masked according to the current
     * question's state (see class Javadoc, decision D4), or {@code null} when no quiz is running.
     *
     * @param boardId  the board UUID from the path
     * @param userId   the caller's {@code public.users.id}
     * @param tenantId the caller's {@code public.tenants.id}
     * @return the active session (masked/demasked per state), or {@code null}
     * @throws BoardNotFoundException if the board is unknown, cross-tenant, or the caller is not a
     *                                member
     */
    public QuizSessionResponse current(final UUID boardId, final Long userId, final Long tenantId) {
        requireBoardAccess(boardId, userId, tenantId);
        return quizSessionRepository
                .findByBoardIdAndTenantIdAndStatus(boardId, tenantId, QuizStatus.ACTIVE)
                .map(session -> QuizResponseAssembler.currentSession(
                        session, questionRepository, choiceRepository, answerRepository))
                .orElse(null);
    }

    /**
     * Returns the board's most recently closed quiz session with its fully demasked results
     * (correct choices, final per-choice distribution and final leaderboard — the quiz is over, so
     * nothing is withheld anymore), or {@code null} when the board has never held one.
     *
     * @param boardId  the board UUID from the path
     * @param userId   the caller's {@code public.users.id}
     * @param tenantId the caller's {@code public.tenants.id}
     * @return the last closed session with aggregated results, or {@code null}
     * @throws BoardNotFoundException if the board is unknown, cross-tenant, or the caller is not a
     *                                member
     */
    public QuizSessionResponse last(final UUID boardId, final Long userId, final Long tenantId) {
        requireBoardAccess(boardId, userId, tenantId);
        return quizSessionRepository
                .findFirstByBoardIdAndTenantIdAndStatusOrderByCreatedAtDesc(boardId, tenantId, QuizStatus.CLOSED)
                .map(session -> QuizResponseAssembler.finalSession(
                        session, questionRepository, choiceRepository, answerRepository))
                .orElse(null);
    }

    /**
     * Resolves the board within the tenant and asserts the caller may access it (owner or member),
     * throwing {@link BoardNotFoundException} otherwise — calques
     * {@code VoteQueryService#requireBoardAccess}, the anti-IDOR primitive shared by every
     * board-scoped read in this module.
     *
     * @param boardId  the board UUID
     * @param userId   the caller's user id
     * @param tenantId the caller's tenant id
     */
    private void requireBoardAccess(final UUID boardId, final Long userId, final Long tenantId) {
        Board board = boardRepository.findByIdAndTenantIdAndDeletedAtIsNull(boardId, tenantId)
                .orElseThrow(() -> new BoardNotFoundException(boardId));
        boolean isMember = board.getOwnerId().equals(userId)
                || boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId).isPresent();
        if (!isMember) {
            throw new BoardNotFoundException(boardId);
        }
    }
}
