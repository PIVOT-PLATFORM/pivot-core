package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.exception.BoardNotFoundException;
import fr.pivot.collaboratif.whiteboard.board.Board;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRepository;
import fr.pivot.collaboratif.whiteboard.quiz.dto.ChoiceResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.ChoiceRevealResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.LeaderboardEntryResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuestionResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuizSessionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * <p>Leaderboard/tally aggregation is implemented privately in this service rather than shared
 * with {@code QuizActionService} (lot C1, mutation side): the module has no shared "quiz-util"
 * package by design (cf. {@code CLAUDE.md} module-contract rule — no ad hoc cross-file coupling
 * inside {@code whiteboard/quiz/} beyond what each lot's own file needs), so a small amount of
 * duplicated scoring logic between the query and action services is an accepted trade-off over
 * introducing a shared internal dependency between two lots developed in parallel.
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
                .map(session -> QuizSessionResponse.of(
                        session, buildCurrentQuestion(session), buildCurrentLeaderboard(session)))
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
                .map(session -> QuizSessionResponse.of(
                        session, buildFinalQuestion(session), buildFinalLeaderboard(session)))
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

    /**
     * Builds the masked/demasked view of an active session's current question, or {@code null} if
     * the quiz has not opened a question yet ({@code currentQuestionIndex} still {@code null}).
     *
     * @param session the active session
     * @return the current question's DTO, or {@code null}
     */
    private QuestionResponse buildCurrentQuestion(final QuizSession session) {
        Integer index = session.getCurrentQuestionIndex();
        if (index == null) {
            return null;
        }
        Question question = questionRepository.findBySessionIdAndPosition(session.getId(), index).orElse(null);
        if (question == null) {
            return null;
        }
        List<Choice> choices = choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(question.getId()));
        int answeredCount = (int) answerRepository.countBySessionIdAndQuestionId(session.getId(), question.getId());
        QuestionState state = session.getCurrentState();
        List<?> choiceDtos = state == QuestionState.REVEALED
                ? revealedChoices(session.getId(), question.getId(), choices)
                : maskedChoices(choices);
        return QuestionResponse.of(question, state == null ? QuestionState.OPEN : state, choiceDtos, answeredCount);
    }

    /**
     * Builds the fully demasked view of a closed session's last question (the one in play when the
     * quiz was stopped), or {@code null} if the quiz was stopped before any question was opened.
     *
     * @param session the closed session
     * @return the last question's fully demasked DTO, or {@code null}
     */
    private QuestionResponse buildFinalQuestion(final QuizSession session) {
        Integer index = session.getCurrentQuestionIndex();
        if (index == null) {
            return null;
        }
        Question question = questionRepository.findBySessionIdAndPosition(session.getId(), index).orElse(null);
        if (question == null) {
            return null;
        }
        List<Choice> choices = choiceRepository.findAllByQuestionIdInOrderByPositionAsc(List.of(question.getId()));
        int answeredCount = (int) answerRepository.countBySessionIdAndQuestionId(session.getId(), question.getId());
        List<ChoiceRevealResponse> choiceDtos = revealedChoices(session.getId(), question.getId(), choices);
        return QuestionResponse.of(question, QuestionState.REVEALED, choiceDtos, answeredCount);
    }

    /**
     * Maps choices to their masked wire form — no {@code correct}, no distribution.
     *
     * @param choices the question's choices
     * @return the masked choice DTOs
     */
    private List<ChoiceResponse> maskedChoices(final List<Choice> choices) {
        return choices.stream().map(ChoiceResponse::of).toList();
    }

    /**
     * Maps choices to their demasked wire form, including the per-choice respondent count computed
     * from the session's answers.
     *
     * @param sessionId  the owning session's UUID
     * @param questionId the question the choices belong to
     * @param choices    the question's choices
     * @return the demasked choice DTOs, each carrying {@code correct} and its tally
     */
    private List<ChoiceRevealResponse> revealedChoices(
            final UUID sessionId, final UUID questionId, final List<Choice> choices) {
        Map<UUID, Long> tally = answerRepository.findAllBySessionId(sessionId).stream()
                .filter(answer -> answer.getQuestionId().equals(questionId))
                .collect(Collectors.groupingBy(Answer::getChoiceId, Collectors.counting()));
        return choices.stream()
                .map(choice -> ChoiceRevealResponse.of(
                        choice, tally.getOrDefault(choice.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * Builds the cumulative leaderboard for an active session, counting only questions that can no
     * longer leak the still-open current question's correct choice: every question strictly before
     * {@code currentQuestionIndex}, plus the current question itself once it has been
     * {@link QuestionState#REVEALED} (see class Javadoc).
     *
     * @param session the active session
     * @return the cumulative leaderboard, ranked; empty if the quiz has not opened a question yet
     */
    private List<LeaderboardEntryResponse> buildCurrentLeaderboard(final QuizSession session) {
        Integer index = session.getCurrentQuestionIndex();
        if (index == null) {
            return List.of();
        }
        boolean includeCurrent = session.getCurrentState() == QuestionState.REVEALED;
        List<Question> counted = questionRepository.findAllBySessionIdOrderByPositionAsc(session.getId()).stream()
                .filter(question -> question.getPosition() < index
                        || (includeCurrent && question.getPosition() == index))
                .toList();
        return buildLeaderboard(session.getId(), counted);
    }

    /**
     * Builds the final leaderboard for a closed session, counting every question — the quiz is
     * over, so no question's correctness remains at risk of premature disclosure.
     *
     * @param session the closed session
     * @return the final leaderboard, ranked
     */
    private List<LeaderboardEntryResponse> buildFinalLeaderboard(final QuizSession session) {
        List<Question> all = questionRepository.findAllBySessionIdOrderByPositionAsc(session.getId());
        return buildLeaderboard(session.getId(), all);
    }

    /**
     * Computes each participant's cumulative score over a set of counted questions (one point per
     * answer whose selected choice is correct) and ranks entries in descending score order, ties
     * sharing a rank (standard competition ranking, e.g. 1, 1, 3).
     *
     * @param sessionId the owning session's UUID
     * @param questions the questions to count toward the score (already filtered by the caller)
     * @return the ranked leaderboard entries; empty if no question is counted
     */
    private List<LeaderboardEntryResponse> buildLeaderboard(final UUID sessionId, final List<Question> questions) {
        if (questions.isEmpty()) {
            return List.of();
        }
        List<UUID> questionIds = questions.stream().map(Question::getId).toList();
        Set<UUID> countedQuestionIds = Set.copyOf(questionIds);
        Map<UUID, Choice> choicesById = choiceRepository
                .findAllByQuestionIdInOrderByPositionAsc(questionIds).stream()
                .collect(Collectors.toMap(Choice::getId, choice -> choice));

        Map<Long, Integer> scores = new HashMap<>();
        for (Answer answer : answerRepository.findAllBySessionId(sessionId)) {
            if (!countedQuestionIds.contains(answer.getQuestionId())) {
                continue;
            }
            Choice choice = choicesById.get(answer.getChoiceId());
            if (choice != null && choice.isCorrect()) {
                scores.merge(answer.getUserId(), 1, Integer::sum);
            }
        }

        List<Map.Entry<Long, Integer>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue()
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
        return ranked.stream()
                .map(entry -> LeaderboardEntryResponse.of(
                        entry.getKey(), entry.getValue(), rankOf(entry.getValue(), ranked)))
                .toList();
    }

    /**
     * Computes a 1-based standard competition rank for a given score within an already
     * score-descending-sorted leaderboard (ties share a rank; the next distinct score skips ahead
     * by the tie count, e.g. 1, 1, 3).
     *
     * @param score  the score to rank
     * @param ranked the full leaderboard, sorted by score descending
     * @return the 1-based rank of the given score
     */
    private int rankOf(final int score, final List<Map.Entry<Long, Integer>> ranked) {
        int rank = 1;
        for (Map.Entry<Long, Integer> other : ranked) {
            if (other.getValue() > score) {
                rank++;
            }
        }
        return rank;
    }
}
