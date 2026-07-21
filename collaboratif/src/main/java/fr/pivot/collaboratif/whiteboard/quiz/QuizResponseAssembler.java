package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.whiteboard.quiz.dto.ChoiceResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.ChoiceRevealResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.LeaderboardEntryResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuestionResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuizSessionResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Package-private assembler building the masked/demasked {@link QuizSessionResponse} wire view of
 * a {@link QuizSession} — the single shared source of truth between {@code QuizActionService}
 * (the broadcast/mutation side, lot C1) and {@code QuizQueryService} (the REST/read side, lot
 * C2). Both call exclusively into this class for session/question/leaderboard construction, which
 * guarantees a client sees byte-identical payloads whether it rehydrates over REST or receives a
 * STOMP broadcast, and removes the duplicated masking/leaderboard logic that previously lived in
 * both services.
 *
 * <p>Every method here is {@code static} and takes its JPA repositories as explicit parameters
 * rather than being a Spring-managed bean: {@code QuizActionService} and {@code QuizQueryService}
 * each already own their own repository instances via constructor injection, and their
 * constructor signatures are load-bearing (tests instantiate both services directly with {@code
 * new}). A static, dependency-free assembler avoids touching either constructor.
 *
 * <p>⚠️ Masking (decision D4, §2.4/§11 of {@code QUIZ-ACTIVITY-DESIGN.md}): the server never
 * returns a choice's {@code correct} flag, nor the per-choice distribution, for the currently
 * in-play question while it is not yet {@link QuestionState#REVEALED} — even to an OWNER/EDITOR.
 * The cumulative leaderboard only ever aggregates questions that are no longer at risk of leaking
 * the current question's correct choice: fully passed questions (position strictly before the
 * current one) plus the current question once it has actually been revealed — never the
 * still-open current question. {@link #finalSession} is used only once a session is closed
 * ({@code quiz:stop} / {@code GET .../quiz/last}), where nothing is withheld anymore regardless of
 * whether the last question was ever explicitly revealed.
 */
final class QuizResponseAssembler {

    private QuizResponseAssembler() {
    }

    /**
     * Builds the masked/demasked view of a session driven purely by its current question's
     * state — used by every quiz broadcast except {@code quiz:session:closed}, and by the
     * {@code GET .../quiz/current} endpoint.
     *
     * @param session            the session (its current question and state already reflect any
     *                           mutation just applied by the caller)
     * @param questionRepository repository used to resolve the current question
     * @param choiceRepository   repository used to resolve the current question's choices
     * @param answerRepository   repository used to compute the answered count / tally / scores
     * @return the session's current wire view
     */
    static QuizSessionResponse currentSession(
            final QuizSession session,
            final QuestionRepository questionRepository,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository) {
        return QuizSessionResponse.of(
                session,
                buildCurrentQuestion(session, questionRepository, choiceRepository, answerRepository),
                buildCurrentLeaderboard(session, questionRepository, choiceRepository, answerRepository));
    }

    /**
     * Builds the fully demasked view of a session at close time — used only for
     * {@code quiz:session:closed} and the {@code GET .../quiz/last} endpoint: the quiz is over, so
     * nothing is withheld anymore, regardless of whether the last question was ever explicitly
     * revealed.
     *
     * @param session            the just-closed session
     * @param questionRepository repository used to resolve the last question in play
     * @param choiceRepository   repository used to resolve that question's choices
     * @param answerRepository   repository used to compute the answered count / tally / scores
     * @return the session's fully demasked final wire view
     */
    static QuizSessionResponse finalSession(
            final QuizSession session,
            final QuestionRepository questionRepository,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository) {
        return QuizSessionResponse.of(
                session,
                buildFinalQuestion(session, questionRepository, choiceRepository, answerRepository),
                buildFinalLeaderboard(session, questionRepository, choiceRepository, answerRepository));
    }

    /**
     * Builds the masked/demasked view of the question currently in play, or {@code null} if the
     * quiz has not opened a question yet.
     *
     * @param session            the session
     * @param questionRepository repository used to resolve the current question
     * @param choiceRepository   repository used to resolve the question's choices
     * @param answerRepository   repository used to compute the answered count / tally
     * @return the current question's DTO, or {@code null}
     */
    private static QuestionResponse buildCurrentQuestion(
            final QuizSession session,
            final QuestionRepository questionRepository,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository) {
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
                ? revealedChoices(session.getId(), question.getId(), choices, answerRepository)
                : maskedChoices(choices);
        return QuestionResponse.of(question, state == null ? QuestionState.OPEN : state, choiceDtos, answeredCount);
    }

    /**
     * Builds the fully demasked view of the last question in play at close time, or {@code null}
     * if the quiz was stopped/closed before any question was opened.
     *
     * @param session            the just-closed session
     * @param questionRepository repository used to resolve the last question in play
     * @param choiceRepository   repository used to resolve that question's choices
     * @param answerRepository   repository used to compute the answered count / tally
     * @return the last question's fully demasked DTO, or {@code null}
     */
    private static QuestionResponse buildFinalQuestion(
            final QuizSession session,
            final QuestionRepository questionRepository,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository) {
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
        List<ChoiceRevealResponse> choiceDtos =
                revealedChoices(session.getId(), question.getId(), choices, answerRepository);
        return QuestionResponse.of(question, QuestionState.REVEALED, choiceDtos, answeredCount);
    }

    /**
     * Maps choices to their masked wire form — no {@code correct}, no distribution.
     *
     * @param choices the question's choices
     * @return the masked choice DTOs
     */
    private static List<ChoiceResponse> maskedChoices(final List<Choice> choices) {
        return choices.stream().map(ChoiceResponse::of).toList();
    }

    /**
     * Maps choices to their demasked wire form, including the per-choice respondent count computed
     * from the session's answers.
     *
     * @param sessionId        the owning session's UUID
     * @param questionId       the question the choices belong to
     * @param choices          the question's choices
     * @param answerRepository repository used to compute the per-choice tally
     * @return the demasked choice DTOs, each carrying {@code correct} and its tally
     */
    private static List<ChoiceRevealResponse> revealedChoices(
            final UUID sessionId,
            final UUID questionId,
            final List<Choice> choices,
            final AnswerRepository answerRepository) {
        Map<UUID, Long> tally = answerRepository.findAllBySessionId(sessionId).stream()
                .filter(answer -> answer.getQuestionId().equals(questionId))
                .collect(Collectors.groupingBy(Answer::getChoiceId, Collectors.counting()));
        return choices.stream()
                .map(choice -> ChoiceRevealResponse.of(choice, tally.getOrDefault(choice.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * Builds the cumulative leaderboard for a session still in play, counting only questions that
     * can no longer leak the still-open current question's correct choice: every question strictly
     * before {@code currentQuestionIndex}, plus the current question itself once it has been
     * {@link QuestionState#REVEALED}.
     *
     * @param session            the session
     * @param questionRepository repository used to resolve the session's questions
     * @param choiceRepository   repository used to resolve counted questions' choices
     * @param answerRepository   repository used to compute per-participant scores
     * @return the cumulative leaderboard, ranked; empty if the quiz has not opened a question yet
     */
    private static List<LeaderboardEntryResponse> buildCurrentLeaderboard(
            final QuizSession session,
            final QuestionRepository questionRepository,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository) {
        Integer index = session.getCurrentQuestionIndex();
        if (index == null) {
            return List.of();
        }
        boolean includeCurrent = session.getCurrentState() == QuestionState.REVEALED;
        List<Question> counted = questionRepository.findAllBySessionIdOrderByPositionAsc(session.getId()).stream()
                .filter(question -> question.getPosition() < index
                        || (includeCurrent && question.getPosition() == index))
                .toList();
        return buildLeaderboard(session.getId(), counted, choiceRepository, answerRepository);
    }

    /**
     * Builds the final leaderboard for a just-closed session, counting every question — the quiz is
     * over, so no question's correctness remains at risk of premature disclosure.
     *
     * @param session            the just-closed session
     * @param questionRepository repository used to resolve every question of the session
     * @param choiceRepository   repository used to resolve those questions' choices
     * @param answerRepository   repository used to compute per-participant scores
     * @return the final leaderboard, ranked
     */
    private static List<LeaderboardEntryResponse> buildFinalLeaderboard(
            final QuizSession session,
            final QuestionRepository questionRepository,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository) {
        List<Question> all = questionRepository.findAllBySessionIdOrderByPositionAsc(session.getId());
        return buildLeaderboard(session.getId(), all, choiceRepository, answerRepository);
    }

    /**
     * Computes each participant's cumulative score over a set of counted questions (one point per
     * answer whose selected choice is correct) and ranks entries in descending score order, ties
     * sharing a rank (standard competition ranking, e.g. 1, 1, 3).
     *
     * @param sessionId        the owning session's UUID
     * @param questions        the questions to count toward the score (already filtered by the
     *                         caller)
     * @param choiceRepository repository used to resolve the counted questions' choices
     * @param answerRepository repository used to fetch the session's answers
     * @return the ranked leaderboard entries; empty if no question is counted
     */
    private static List<LeaderboardEntryResponse> buildLeaderboard(
            final UUID sessionId,
            final List<Question> questions,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository) {
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
    private static int rankOf(final int score, final List<Map.Entry<Long, Integer>> ranked) {
        int rank = 1;
        for (Map.Entry<Long, Integer> other : ranked) {
            if (other.getValue() > score) {
                rank++;
            }
        }
        return rank;
    }
}
