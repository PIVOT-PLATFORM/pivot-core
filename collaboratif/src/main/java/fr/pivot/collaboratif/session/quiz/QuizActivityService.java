package fr.pivot.collaboratif.session.quiz;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Participant;
import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.quiz.dto.LeaderboardEntry;
import fr.pivot.collaboratif.session.quiz.dto.QuestionEndedEvent;
import fr.pivot.collaboratif.session.quiz.dto.QuestionStartedEvent;
import fr.pivot.collaboratif.session.quiz.dto.QuizAnsweredEvent;
import fr.pivot.collaboratif.session.quiz.dto.QuizResultsDto;
import fr.pivot.collaboratif.session.quiz.dto.QuizStateDto;
import fr.pivot.collaboratif.session.quiz.dto.SubmitAnswerRequest;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for the QUIZ activity type (US19.3.1) — a facilitator-paced, server-timed,
 * multiplayer quiz with speed-bonus scoring and a live leaderboard.
 *
 * <p><strong>Timing is server-authoritative.</strong> The facilitator opens each question
 * ({@link #next}); the backend stamps its start time and alone decides whether an answer arrived
 * within the window ({@link #answer} rejects late/closed submissions) — the client countdown is
 * display-only. Score = base points if correct, plus a speed bonus that decreases with the
 * submission rank among correct answers (first correct answer gets the full bonus). The correct
 * answer is never broadcast until the facilitator ends the question ({@link #endCurrent}).
 */
@Service
public class QuizActivityService {

    private static final int DEFAULT_POINTS = 100;
    private static final int DEFAULT_SPEED_BONUS_MAX = 50;
    private static final int SPEED_BONUS_STEP = 10;
    private static final int DEFAULT_DURATION_SECONDS = 20;

    private final SessionQuizStateRepository stateRepository;
    private final SessionQuizAnswerRepository answerRepository;
    private final ParticipantRepository participantRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service with its required dependencies.
     *
     * @param stateRepository       repository for quiz progression
     * @param answerRepository      repository for answers
     * @param participantRepository repository used to resolve leaderboard display names
     * @param messagingTemplate     STOMP broadcaster
     * @param objectMapper          JSON (de)serializer for config and answer payloads
     */
    public QuizActivityService(
            final SessionQuizStateRepository stateRepository,
            final SessionQuizAnswerRepository answerRepository,
            final ParticipantRepository participantRepository,
            final SimpMessagingTemplate messagingTemplate,
            final ObjectMapper objectMapper) {
        this.stateRepository = stateRepository;
        this.answerRepository = answerRepository;
        this.participantRepository = participantRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns whether this service handles the given session type.
     *
     * @param type the session type
     * @return {@code true} for {@link SessionType#QUIZ}
     */
    public boolean supports(final SessionType type) {
        return type == SessionType.QUIZ;
    }

    /**
     * Advances to (and opens) the next question — facilitator action (US19.3.1).
     *
     * @param session the LIVE QUIZ session
     * @throws InvalidSessionStatusException if the session is not a LIVE QUIZ
     * @throws SessionConflictException      if there is no next question (the quiz is finished)
     */
    @Transactional
    public void next(final Session session) {
        requireLiveQuiz(session);
        List<QuizQuestion> questions = readQuestions(session);
        SessionQuizState state = stateRepository.findBySessionId(session.getId())
                .orElseGet(() -> new SessionQuizState(session.getId()));
        int nextIndex = state.getCurrentQuestionIndex() + 1;
        if (nextIndex >= questions.size()) {
            throw new SessionConflictException("QUIZ_FINISHED", "No more questions");
        }
        state.startQuestion(nextIndex, Instant.now());
        stateRepository.save(state);

        QuizQuestion question = questions.get(nextIndex);
        broadcast(session.getId(), new QuestionStartedEvent(
                session.getId(), nextIndex, questions.size(),
                question.text(), question.options(), question.durationSeconds()));
    }

    /**
     * Ends the current question, revealing the correct answer and the refreshed leaderboard —
     * facilitator action (US19.3.1).
     *
     * @param session the QUIZ session
     * @throws SessionValidationException if no question has been started yet
     */
    @Transactional
    public void endCurrent(final Session session) {
        SessionQuizState state = stateRepository.findBySessionId(session.getId())
                .orElseThrow(() -> new SessionValidationException("QUIZ_NOT_STARTED", "No question started"));
        int index = state.getCurrentQuestionIndex();
        if (index < 0) {
            throw new SessionValidationException("QUIZ_NOT_STARTED", "No question started");
        }
        state.endQuestion();
        stateRepository.save(state);

        List<Integer> correct = new ArrayList<>(readQuestions(session).get(index).correctIndices());
        correct.sort(Comparator.naturalOrder());
        broadcast(session.getId(),
                new QuestionEndedEvent(session.getId(), index, correct, leaderboard(session.getId())));
    }

    /**
     * Records a participant's answer to the live question, graded with a speed bonus (US19.3.1).
     *
     * @param session       the LIVE QUIZ session
     * @param participantId the answering participant's id
     * @param request       the answer (question index + selected option indices)
     * @throws InvalidSessionStatusException if the session is not a LIVE QUIZ
     * @throws SessionValidationException    if the answer targets a question that is not the live one
     * @throws SessionConflictException      if the window is closed, or the participant already
     *                                       answered this question
     */
    @Transactional
    public void answer(final Session session, final UUID participantId, final SubmitAnswerRequest request) {
        requireLiveQuiz(session);
        List<QuizQuestion> questions = readQuestions(session);
        SessionQuizState state = stateRepository.findBySessionId(session.getId())
                .orElseThrow(() -> new SessionValidationException("WRONG_QUESTION", "No question is live"));

        int index = state.getCurrentQuestionIndex();
        if (index < 0 || request.questionIndex() != index) {
            throw new SessionValidationException("WRONG_QUESTION", "Answer is not for the live question");
        }
        if (state.isQuestionEnded() || isPastWindow(state, questions.get(index))) {
            throw new SessionConflictException("QUESTION_CLOSED", "The answer window has closed");
        }
        if (answerRepository.existsBySessionIdAndParticipantIdAndQuestionIndex(
                session.getId(), participantId, index)) {
            throw new SessionConflictException("ALREADY_ANSWERED", "Participant already answered this question");
        }

        QuizQuestion question = questions.get(index);
        boolean correct = new HashSet<>(request.selectedIndices()).equals(question.correctIndices());
        int points = correct ? scoreFor(session) : 0;
        answerRepository.save(new SessionQuizAnswer(
                session.getId(), participantId, index, write(request.selectedIndices()), correct, points, Instant.now()));

        long answerCount = answerRepository.findAllBySessionIdAndQuestionIndex(session.getId(), index).size();
        broadcast(session.getId(), new QuizAnsweredEvent(session.getId(), index, answerCount));
    }

    /**
     * Builds the participant-safe reconnect snapshot (US19.3.1) — current question (correct answer
     * withheld until ended), the caller's own score and whether they already answered.
     *
     * @param session       the QUIZ session
     * @param participantId the requesting participant's id
     * @return the reconnect snapshot
     */
    @Transactional(readOnly = true)
    public QuizStateDto getState(final Session session, final UUID participantId) {
        List<QuizQuestion> questions = readQuestions(session);
        SessionQuizState state = stateRepository.findBySessionId(session.getId()).orElse(null);
        int myScore = scoreOf(session.getId(), participantId);
        if (state == null || state.getCurrentQuestionIndex() < 0) {
            return new QuizStateDto(false, -1, questions.size(), null, List.of(), null, null,
                    true, false, myScore, List.of(), List.of());
        }
        int index = state.getCurrentQuestionIndex();
        QuizQuestion question = questions.get(index);
        boolean answered = answerRepository.existsBySessionIdAndParticipantIdAndQuestionIndex(
                session.getId(), participantId, index);
        boolean ended = state.isQuestionEnded();
        List<Integer> correct = ended ? sortedCorrect(question) : List.of();
        List<LeaderboardEntry> board = ended ? leaderboard(session.getId()) : List.of();
        return new QuizStateDto(
                true, index, questions.size(), question.text(), question.options(),
                question.durationSeconds(), state.getQuestionStartedAt(), ended, answered, myScore, correct, board);
    }

    /**
     * Computes the final results — full ranking plus each question's correct-rate (US19.3.1).
     *
     * @param session the QUIZ session
     * @return the final results
     */
    @Transactional(readOnly = true)
    public QuizResultsDto getResults(final Session session) {
        List<QuizQuestion> questions = readQuestions(session);
        List<Double> rates = new ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            List<SessionQuizAnswer> answers = answerRepository.findAllBySessionIdAndQuestionIndex(session.getId(), i);
            long correct = answers.stream().filter(SessionQuizAnswer::isCorrect).count();
            rates.add(answers.isEmpty() ? 0.0 : (double) correct / answers.size());
        }
        return new QuizResultsDto(leaderboard(session.getId()), rates);
    }

    // --- internals ------------------------------------------------------------------------------

    private List<LeaderboardEntry> leaderboard(final UUID sessionId) {
        Map<UUID, Integer> scores = new HashMap<>();
        for (SessionQuizAnswer answer : answerRepository.findAllBySessionId(sessionId)) {
            scores.merge(answer.getParticipantId(), answer.getPointsAwarded(), Integer::sum);
        }
        Map<UUID, String> names = new HashMap<>();
        for (Participant participant : participantRepository.findAllById(scores.keySet())) {
            names.put(participant.getId(), participant.getDisplayName());
        }
        List<LeaderboardEntry> board = new ArrayList<>(scores.size());
        scores.forEach((participantId, score) ->
                board.add(new LeaderboardEntry(participantId, names.get(participantId), score)));
        board.sort(Comparator.comparingInt(LeaderboardEntry::score).reversed()
                .thenComparing(e -> e.displayName() == null ? "" : e.displayName()));
        return board;
    }

    private int scoreOf(final UUID sessionId, final UUID participantId) {
        return answerRepository.findAllBySessionId(sessionId).stream()
                .filter(a -> a.getParticipantId().equals(participantId))
                .mapToInt(SessionQuizAnswer::getPointsAwarded)
                .sum();
    }

    private int scoreFor(final Session session) {
        long priorCorrect = answerRepository.countBySessionIdAndQuestionIndexAndCorrectTrue(
                session.getId(), currentIndex(session));
        int rank = (int) priorCorrect + 1;
        int bonus = Math.max(0, readSpeedBonusMax(session) - (rank - 1) * SPEED_BONUS_STEP);
        return readPoints(session) + bonus;
    }

    private int currentIndex(final Session session) {
        return stateRepository.findBySessionId(session.getId())
                .map(SessionQuizState::getCurrentQuestionIndex)
                .orElse(-1);
    }

    private boolean isPastWindow(final SessionQuizState state, final QuizQuestion question) {
        Instant startedAt = state.getQuestionStartedAt();
        return startedAt == null
                || Duration.between(startedAt, Instant.now()).getSeconds() > question.durationSeconds();
    }

    private List<Integer> sortedCorrect(final QuizQuestion question) {
        List<Integer> correct = new ArrayList<>(question.correctIndices());
        correct.sort(Comparator.naturalOrder());
        return correct;
    }

    private List<QuizQuestion> readQuestions(final Session session) {
        JsonNode questions = config(session).get("questions");
        if (questions == null || !questions.isArray() || questions.isEmpty()) {
            throw new SessionValidationException("QUIZ_NOT_CONFIGURED", "Quiz has no questions configured");
        }
        List<QuizQuestion> parsed = new ArrayList<>(questions.size());
        for (JsonNode q : questions) {
            List<String> options = new ArrayList<>();
            JsonNode optionsNode = q.get("options");
            if (optionsNode != null) {
                optionsNode.forEach(o -> options.add(o.asText()));
            }
            Set<Integer> correct = new LinkedHashSet<>();
            JsonNode correctNode = q.get("correctIndices");
            if (correctNode != null && correctNode.isArray()) {
                correctNode.forEach(c -> correct.add(c.asInt()));
            } else if (q.get("correctIndex") != null) {
                correct.add(q.get("correctIndex").asInt());
            }
            int duration = q.get("durationSeconds") != null
                    ? q.get("durationSeconds").asInt(DEFAULT_DURATION_SECONDS) : DEFAULT_DURATION_SECONDS;
            String text = q.get("text") != null ? q.get("text").asText() : "";
            parsed.add(new QuizQuestion(text, options, correct, duration));
        }
        return parsed;
    }

    private int readPoints(final Session session) {
        JsonNode node = config(session).get("pointsPerQuestion");
        return node != null ? node.asInt(DEFAULT_POINTS) : DEFAULT_POINTS;
    }

    private int readSpeedBonusMax(final Session session) {
        JsonNode node = config(session).get("speedBonusMax");
        return node != null ? node.asInt(DEFAULT_SPEED_BONUS_MAX) : DEFAULT_SPEED_BONUS_MAX;
    }

    private JsonNode config(final Session session) {
        try {
            return objectMapper.readTree(session.getConfig() == null ? "{}" : session.getConfig());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read session config", e);
        }
    }

    private String write(final Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize quiz answer", e);
        }
    }

    private void requireLiveQuiz(final Session session) {
        if (session.getType() != SessionType.QUIZ || session.getStatus() != SessionStatus.LIVE) {
            throw new InvalidSessionStatusException("Session is not a LIVE quiz");
        }
    }

    private void broadcast(final UUID sessionId, final Object event) {
        messagingTemplate.convertAndSend(SessionDestinations.topicFor(sessionId), event);
    }
}
