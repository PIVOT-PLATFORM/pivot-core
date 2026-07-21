package fr.pivot.collaboratif.whiteboard.quiz;

import fr.pivot.collaboratif.whiteboard.LogSanitizer;
import fr.pivot.collaboratif.whiteboard.board.BoardMemberRepository;
import fr.pivot.collaboratif.whiteboard.board.BoardRole;
import fr.pivot.collaboratif.whiteboard.canvas.dto.BroadcastCanvasMessage;
import fr.pivot.collaboratif.whiteboard.canvas.dto.CanvasActionMessage;
import fr.pivot.collaboratif.whiteboard.quiz.dto.ChoiceResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.ChoiceRevealResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.LeaderboardEntryResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuestionResponse;
import fr.pivot.collaboratif.whiteboard.quiz.dto.QuizSessionResponse;
import fr.pivot.collaboratif.whiteboard.ws.StompPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for facilitator-driven MCQ quiz STOMP actions (Quiz feature, Kahoot/Klaxoon-style
 * workshop facilitation). Sibling of {@code VoteActionService} — this quiz module calques the
 * dot-voting activity file for file, with one structuring divergence: the correct answer(s) of the
 * question currently in play must never reach a participant before the facilitator reveals it (see
 * {@code QUIZ-ACTIVITY-DESIGN.md} §2.4/§9). The {@code WhiteboardActionController} routes every
 * {@code quiz:*} action envelope here, exactly as it already does for {@code vote:*}.
 *
 * <p><strong>Wire contract</strong> (aligned on the frontend's {@code board.store.ts}, §5 of the
 * design doc). All frames travel as {@code { type, data }} envelopes on the shared action/broadcast
 * channels, exactly like canvas and vote actions.
 *
 * <p>Inbound ({@code /app/whiteboard/{boardId}/action}):
 * <ul>
 *   <li>{@code quiz:start} — {@code { boardId, questions: [{ text, choices: [{ text, correct }] }] }}
 *       (OWNER/EDITOR only)</li>
 *   <li>{@code quiz:answer} — {@code { sessionId, boardId, questionId, choiceId }} (any member)</li>
 *   <li>{@code quiz:next} — {@code { sessionId, boardId }} (OWNER/EDITOR only)</li>
 *   <li>{@code quiz:reveal} — {@code { sessionId, boardId }} (OWNER/EDITOR only)</li>
 *   <li>{@code quiz:stop} — {@code { sessionId, boardId }} (OWNER/EDITOR only)</li>
 * </ul>
 *
 * <p>Outbound ({@code /topic/whiteboard/{boardId}}), {@code data} is a {@link QuizSessionResponse}:
 * <ul>
 *   <li>{@code quiz:session:started} — on start, masked (no {@code correct}, {@code answeredCount=0})</li>
 *   <li>{@code quiz:updated} — on answer/next/reveal; masked while the current question is
 *       {@code OPEN} (only {@code answeredCount}, never the per-choice distribution), demasked
 *       (distribution + {@code correct} + leaderboard) once {@code REVEALED}</li>
 *   <li>{@code quiz:session:closed} — on stop, fully demasked with the final leaderboard</li>
 * </ul>
 *
 * <p>Every refusal is silent (no broadcast, no error frame), matching the vote/canvas mutations'
 * posture — a non-manager starting/advancing/revealing/stopping, an invalid question set, a stale or
 * foreign {@code choiceId}/{@code questionId}, a malformed id: all dropped with a DEBUG log. Board
 * membership itself is already enforced upstream by {@code WhiteboardChannelInterceptor} before any
 * frame reaches this service (EN08.1).
 *
 * <p><strong>Masking by construction.</strong> The masked/demasked question and leaderboard views
 * are built by {@link #buildCurrentSessionResponse(QuizSession)} (driven purely by
 * {@code QuizSession#getCurrentState()}) and {@link #buildFinalSessionResponse(QuizSession)} (used
 * only at {@code quiz:stop}, always fully demasked). Both replicate — deliberately duplicated, not
 * shared — the exact same construction logic as {@code QuizQueryService} (the read-side, lot C2), so
 * a client sees identical results whether it rehydrates over REST or receives a broadcast (see class
 * Javadoc there for the rationale: no shared "quiz-util" file inside {@code whiteboard/quiz/}).
 *
 * <p><strong>Anti-race.</strong> {@code quiz:answer}/{@code quiz:next}/{@code quiz:reveal}/
 * {@code quiz:stop} load the session via {@link QuizSessionRepository#findForUpdate} (pessimistic
 * row lock), making the upsert-then-broadcast and state-transition sequences atomic against a
 * concurrent action from another tab — the same guarantee {@code VoteActionService} relies on for
 * cast/uncast. {@code quiz:start} instead races on the partial unique index enforcing at most one
 * {@link QuizStatus#ACTIVE} session per board ({@code uq_quiz_session_active_per_board}, see
 * {@code V9__quiz.sql}); losing that race surfaces as a {@link DataIntegrityViolationException},
 * caught and dropped silently exactly like {@code VoteActionService#handleStart}.
 */
@Service
@Transactional
public class QuizActionService {

    private static final Logger LOG = LoggerFactory.getLogger(QuizActionService.class);

    private static final String BOARD_TOPIC_PREFIX = "/topic/whiteboard/";

    /** Inbound action types. */
    private static final String IN_START = "quiz:start";
    private static final String IN_ANSWER = "quiz:answer";
    private static final String IN_NEXT = "quiz:next";
    private static final String IN_REVEAL = "quiz:reveal";
    private static final String IN_STOP = "quiz:stop";

    /** Outbound broadcast types (exact frontend subscription names). */
    private static final String OUT_SESSION_STARTED = "quiz:session:started";
    private static final String OUT_UPDATED = "quiz:updated";
    private static final String OUT_SESSION_CLOSED = "quiz:session:closed";

    /** Minimum number of choices a question must have (§2.3 AC). */
    private static final int MIN_CHOICES = 2;

    /** Maximum number of choices a question may have (§2.3 AC). */
    private static final int MAX_CHOICES = 6;

    /**
     * Defensive upper bound on the number of questions accepted at {@code quiz:start}. The
     * practical bound in normal use is the frontend's own {@code MAX_QUESTIONS} plus the 64 KB
     * STOMP frame limit (§9 point 8 of the design doc, {@code CollaboratifWebSocketConfig}), which
     * rejects an oversized frame before it ever reaches this service; this constant is a
     * defence-in-depth ceiling against a client bypassing the frontend bound (mirrors
     * {@code VoteActionService#MAX_VOTES_PER_PERSON}).
     */
    private static final int MAX_QUESTIONS = 100;

    /** Matches {@code Question.text}'s column width ({@code V9__quiz.sql}). */
    private static final int MAX_QUESTION_TEXT_LENGTH = 500;

    /** Matches {@code Choice.text}'s column width ({@code V9__quiz.sql}). */
    private static final int MAX_CHOICE_TEXT_LENGTH = 300;

    private final SimpMessagingTemplate messagingTemplate;
    private final QuizSessionRepository quizSessionRepository;
    private final QuestionRepository questionRepository;
    private final ChoiceRepository choiceRepository;
    private final AnswerRepository answerRepository;
    private final BoardMemberRepository boardMemberRepository;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param messagingTemplate      STOMP broadcast template
     * @param quizSessionRepository  JPA repository for {@link QuizSession}
     * @param questionRepository     JPA repository for {@link Question}
     * @param choiceRepository       JPA repository for {@link Choice}
     * @param answerRepository       JPA repository for {@link Answer}
     * @param boardMemberRepository  JPA repository for role lookups (start/next/reveal/stop guard)
     * @param objectMapper           Jackson mapper for serialising the session DTO into the
     *                               broadcast payload map
     */
    public QuizActionService(
            final SimpMessagingTemplate messagingTemplate,
            final QuizSessionRepository quizSessionRepository,
            final QuestionRepository questionRepository,
            final ChoiceRepository choiceRepository,
            final AnswerRepository answerRepository,
            final BoardMemberRepository boardMemberRepository,
            final ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.quizSessionRepository = quizSessionRepository;
        this.questionRepository = questionRepository;
        this.choiceRepository = choiceRepository;
        this.answerRepository = answerRepository;
        this.boardMemberRepository = boardMemberRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatches an incoming {@code quiz:*} action to its handler after resolving the type. An
     * unknown {@code quiz:*} type (should not occur — the controller only routes the five known
     * ones) is dropped with a WARN log.
     *
     * @param boardId   the target board UUID (from the STOMP destination path variable)
     * @param message   the incoming action envelope
     * @param principal the authenticated STOMP session principal
     */
    public void handle(final UUID boardId, final CanvasActionMessage message, final StompPrincipal principal) {
        Map<String, Object> data = asMap(message.data());
        switch (message.type()) {
            case IN_START -> handleStart(boardId, data, principal);
            case IN_ANSWER -> handleAnswer(boardId, data, principal);
            case IN_NEXT -> handleNext(boardId, data, principal);
            case IN_REVEAL -> handleReveal(boardId, data, principal);
            case IN_STOP -> handleStop(boardId, data, principal);
            default -> LOG.warn("Unknown quiz action type '{}' — dropped board={} user={}",
                    LogSanitizer.forLog(message.type()), boardId, principal.userId());
        }
    }

    /**
     * Handles {@code quiz:start}: validates the composed question set (OWNER/EDITOR only), refuses
     * if a session is already active on the board, then persists the session with its questions and
     * choices and opens question 0. Broadcasts {@code quiz:session:started} (masked).
     */
    private void handleStart(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        if (!canManage(boardId, principal.userId())) {
            LOG.debug("quiz:start refused (not OWNER/EDITOR): user={} board={}", principal.userId(), boardId);
            return;
        }
        if (quizSessionRepository.existsByBoardIdAndStatus(boardId, QuizStatus.ACTIVE)) {
            LOG.debug("quiz:start refused (a session is already active): board={}", boardId);
            return;
        }
        List<ParsedQuestion> parsed = parseAndValidateQuestions(data.get("questions"));
        if (parsed == null) {
            LOG.debug("quiz:start refused (invalid or missing question set): board={}", boardId);
            return;
        }

        QuizSession session = new QuizSession(boardId, principal.tenantId(), Instant.now());
        try {
            session = quizSessionRepository.save(session);
            for (int qi = 0; qi < parsed.size(); qi++) {
                ParsedQuestion parsedQuestion = parsed.get(qi);
                Question question = questionRepository.save(
                        new Question(session.getId(), qi, parsedQuestion.text(), null));
                List<ParsedChoice> choices = parsedQuestion.choices();
                for (int ci = 0; ci < choices.size(); ci++) {
                    ParsedChoice parsedChoice = choices.get(ci);
                    choiceRepository.save(new Choice(
                            question.getId(), ci, parsedChoice.text(), parsedChoice.correct()));
                }
            }
            session.setCurrentQuestionIndex(0);
            session.setCurrentState(QuestionState.OPEN);
            session = quizSessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent start (partial unique index on ACTIVE per board).
            // The other start's session is authoritative — drop silently, do not broadcast twice.
            LOG.debug("quiz:start lost active-session race: board={}", boardId);
            return;
        }
        broadcast(boardId, principal, OUT_SESSION_STARTED, buildCurrentSessionResponse(session));
        LOG.info("Quiz started: session={} board={} questions={}", session.getId(), boardId, parsed.size());
    }

    /**
     * Handles {@code quiz:answer}: upserts the caller's answer to the currently {@code OPEN}
     * question, provided the referenced question is indeed the one in play and the choice belongs
     * to it. Broadcasts {@code quiz:updated} (masked — only {@code answeredCount} changes visibly,
     * never the per-choice distribution).
     */
    private void handleAnswer(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        QuizSession session = lockActiveSession(boardId, data, principal);
        if (session == null) {
            return;
        }
        Integer index = session.getCurrentQuestionIndex();
        if (index == null || session.getCurrentState() != QuestionState.OPEN) {
            LOG.debug("quiz:answer refused (no open question): session={} board={}", session.getId(), boardId);
            return;
        }
        UUID questionId = parseUuid(data.get("questionId"));
        if (questionId == null) {
            return;
        }
        Question current = questionRepository.findBySessionIdAndPosition(session.getId(), index).orElse(null);
        if (current == null || !current.getId().equals(questionId)) {
            LOG.debug("quiz:answer refused (stale/foreign questionId): session={} question={}",
                    session.getId(), questionId);
            return;
        }
        UUID choiceId = parseUuid(data.get("choiceId"));
        if (choiceId == null || choiceRepository.countByIdAndQuestionId(choiceId, current.getId()) != 1) {
            LOG.debug("quiz:answer refused (missing/foreign choice): question={} choice={}",
                    current.getId(), choiceId);
            return;
        }
        Instant now = Instant.now();
        Optional<Answer> existing = answerRepository.findBySessionIdAndQuestionIdAndUserId(
                session.getId(), current.getId(), principal.userId());
        if (existing.isPresent()) {
            Answer answer = existing.get();
            answer.setChoiceId(choiceId);
            answer.setAnsweredAt(now);
            answerRepository.save(answer);
        } else {
            answerRepository.save(new Answer(session.getId(), current.getId(), choiceId, principal.userId(), now));
        }
        broadcast(boardId, principal, OUT_UPDATED, buildCurrentSessionResponse(session));
        LOG.debug("Quiz answered: session={} question={} user={}",
                session.getId(), current.getId(), principal.userId());
    }

    /**
     * Handles {@code quiz:next}: closes the currently in-play question and opens the next one
     * (OWNER/EDITOR only). Decision D3 (§11 of the design doc): if there is no further question,
     * the action is dropped silently — the facilitator must close the quiz explicitly via
     * {@code quiz:stop}. Broadcasts {@code quiz:updated} (masked).
     */
    private void handleNext(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        if (!canManage(boardId, principal.userId())) {
            LOG.debug("quiz:next refused (not OWNER/EDITOR): user={} board={}", principal.userId(), boardId);
            return;
        }
        QuizSession session = lockActiveSession(boardId, data, principal);
        if (session == null) {
            return;
        }
        Integer index = session.getCurrentQuestionIndex();
        if (index == null) {
            LOG.debug("quiz:next refused (quiz not started): session={} board={}", session.getId(), boardId);
            return;
        }
        int nextIndex = index + 1;
        Question next = questionRepository.findBySessionIdAndPosition(session.getId(), nextIndex).orElse(null);
        if (next == null) {
            LOG.debug("quiz:next no-op (no further question, decision D3): session={} board={}",
                    session.getId(), boardId);
            return;
        }
        session.setCurrentQuestionIndex(nextIndex);
        session.setCurrentState(QuestionState.OPEN);
        session = quizSessionRepository.save(session);
        broadcast(boardId, principal, OUT_UPDATED, buildCurrentSessionResponse(session));
        LOG.debug("Quiz advanced: session={} board={} question={}", session.getId(), boardId, nextIndex);
    }

    /**
     * Handles {@code quiz:reveal}: transitions the currently {@code OPEN} question to
     * {@code REVEALED} (OWNER/EDITOR only). Broadcasts {@code quiz:updated} demasked — per-choice
     * distribution, {@code correct} flags and the cumulative leaderboard (now including this
     * question).
     */
    private void handleReveal(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        if (!canManage(boardId, principal.userId())) {
            LOG.debug("quiz:reveal refused (not OWNER/EDITOR): user={} board={}", principal.userId(), boardId);
            return;
        }
        QuizSession session = lockActiveSession(boardId, data, principal);
        if (session == null) {
            return;
        }
        if (session.getCurrentQuestionIndex() == null || session.getCurrentState() != QuestionState.OPEN) {
            LOG.debug("quiz:reveal refused (no open question): session={} board={}", session.getId(), boardId);
            return;
        }
        session.setCurrentState(QuestionState.REVEALED);
        session = quizSessionRepository.save(session);
        broadcast(boardId, principal, OUT_UPDATED, buildCurrentSessionResponse(session));
        LOG.info("Quiz revealed: session={} board={} question={}",
                session.getId(), boardId, session.getCurrentQuestionIndex());
    }

    /**
     * Handles {@code quiz:stop}: closes the active session (OWNER/EDITOR only). Broadcasts
     * {@code quiz:session:closed} fully demasked, with the final leaderboard computed over every
     * question — consistent with {@code QuizQueryService#last}.
     */
    private void handleStop(final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        if (!canManage(boardId, principal.userId())) {
            LOG.debug("quiz:stop refused (not OWNER/EDITOR): user={} board={}", principal.userId(), boardId);
            return;
        }
        QuizSession session = lockActiveSession(boardId, data, principal);
        if (session == null) {
            return;
        }
        session.setStatus(QuizStatus.CLOSED);
        session.setClosedAt(Instant.now());
        session = quizSessionRepository.save(session);
        broadcast(boardId, principal, OUT_SESSION_CLOSED, buildFinalSessionResponse(session));
        LOG.info("Quiz stopped: session={} board={}", session.getId(), boardId);
    }

    // -------------------------------------------------------------------------
    // Session response construction (masking) — deliberately duplicated from
    // QuizQueryService (lot C2); see class Javadoc.
    // -------------------------------------------------------------------------

    /**
     * Builds the masked/demasked view of a session driven purely by its current question's state —
     * used by every broadcast except {@code quiz:session:closed}. Replicates
     * {@code QuizQueryService#current}/{@code #buildCurrentQuestion}/{@code #buildCurrentLeaderboard}.
     *
     * @param session the session (its current question and state already reflect the mutation just
     *                applied by the caller)
     * @return the session's current wire view
     */
    private QuizSessionResponse buildCurrentSessionResponse(final QuizSession session) {
        return QuizSessionResponse.of(session, buildCurrentQuestionResponse(session), buildCurrentLeaderboard(session));
    }

    /**
     * Builds the fully demasked view of a session at close time — used only for
     * {@code quiz:session:closed}. Replicates {@code QuizQueryService#last}/{@code #buildFinalQuestion}/
     * {@code #buildFinalLeaderboard}: the quiz is over, so nothing is withheld anymore, regardless of
     * whether the last question was ever explicitly revealed.
     *
     * @param session the just-closed session
     * @return the session's fully demasked final wire view
     */
    private QuizSessionResponse buildFinalSessionResponse(final QuizSession session) {
        return QuizSessionResponse.of(session, buildFinalQuestionResponse(session), buildFinalLeaderboard(session));
    }

    /**
     * Builds the masked/demasked view of the question currently in play, or {@code null} if the
     * quiz has not opened a question yet.
     *
     * @param session the session
     * @return the current question's DTO, or {@code null}
     */
    private QuestionResponse buildCurrentQuestionResponse(final QuizSession session) {
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
     * Builds the fully demasked view of the last question in play at close time, or {@code null} if
     * the quiz was stopped before any question was opened.
     *
     * @param session the just-closed session
     * @return the last question's fully demasked DTO, or {@code null}
     */
    private QuestionResponse buildFinalQuestionResponse(final QuizSession session) {
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
                .map(choice -> ChoiceRevealResponse.of(choice, tally.getOrDefault(choice.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * Builds the cumulative leaderboard for a session still in play, counting only questions that
     * can no longer leak the still-open current question's correct choice: every question strictly
     * before {@code currentQuestionIndex}, plus the current question itself once it has been
     * {@link QuestionState#REVEALED}.
     *
     * @param session the session
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
     * Builds the final leaderboard for a just-closed session, counting every question — the quiz is
     * over, so no question's correctness remains at risk of premature disclosure.
     *
     * @param session the just-closed session
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
     * score-descending-sorted leaderboard (ties share a rank; the next distinct score skips ahead by
     * the tie count, e.g. 1, 1, 3).
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

    // -------------------------------------------------------------------------
    // Inbound payload parsing / validation
    // -------------------------------------------------------------------------

    /**
     * A single question parsed and validated out of the {@code quiz:start} payload, ready to
     * persist.
     *
     * @param text    the trimmed, non-empty prompt text
     * @param choices the parsed, validated choices (2–6, at least one correct)
     */
    private record ParsedQuestion(String text, List<ParsedChoice> choices) {
    }

    /**
     * A single answer choice parsed and validated out of the {@code quiz:start} payload.
     *
     * @param text    the trimmed, non-empty label text
     * @param correct whether this choice is (one of) the correct answer(s)
     */
    private record ParsedChoice(String text, boolean correct) {
    }

    /**
     * Parses and validates the {@code questions} field of a {@code quiz:start} payload against the
     * §2.3 acceptance criteria (≥1 question, bounded by {@link #MAX_QUESTIONS}; 2–6 choices per
     * question; at least one correct choice; non-empty texts within their column widths). Any
     * structural or semantic violation yields {@code null} so the caller drops the whole action —
     * there is no partial acceptance.
     *
     * @param rawQuestions the raw {@code data.questions} value
     * @return the parsed, validated question set, or {@code null} if invalid
     */
    private List<ParsedQuestion> parseAndValidateQuestions(final Object rawQuestions) {
        if (!(rawQuestions instanceof List<?> rawList) || rawList.isEmpty() || rawList.size() > MAX_QUESTIONS) {
            return null;
        }
        List<ParsedQuestion> result = new ArrayList<>();
        for (Object rawQuestion : rawList) {
            ParsedQuestion parsedQuestion = parseQuestion(rawQuestion);
            if (parsedQuestion == null) {
                return null;
            }
            result.add(parsedQuestion);
        }
        return result;
    }

    /**
     * Parses and validates a single raw question map, or returns {@code null} if it does not
     * satisfy the §2.3 acceptance criteria.
     *
     * @param rawQuestion the raw element of {@code data.questions}
     * @return the parsed question, or {@code null} if invalid
     */
    private ParsedQuestion parseQuestion(final Object rawQuestion) {
        if (!(rawQuestion instanceof Map<?, ?> questionMap)) {
            return null;
        }
        String text = asTrimmedString(questionMap.get("text"));
        if (text == null || text.isEmpty() || text.length() > MAX_QUESTION_TEXT_LENGTH) {
            return null;
        }
        if (!(questionMap.get("choices") instanceof List<?> rawChoices)
                || rawChoices.size() < MIN_CHOICES
                || rawChoices.size() > MAX_CHOICES) {
            return null;
        }
        List<ParsedChoice> choices = new ArrayList<>();
        boolean hasCorrect = false;
        for (Object rawChoice : rawChoices) {
            ParsedChoice parsedChoice = parseChoice(rawChoice);
            if (parsedChoice == null) {
                return null;
            }
            hasCorrect = hasCorrect || parsedChoice.correct();
            choices.add(parsedChoice);
        }
        if (!hasCorrect) {
            return null;
        }
        return new ParsedQuestion(text, choices);
    }

    /**
     * Parses and validates a single raw choice map, or returns {@code null} if it does not satisfy
     * the §2.3 acceptance criteria (non-empty text within its column width).
     *
     * @param rawChoice the raw element of {@code data.questions[i].choices}
     * @return the parsed choice, or {@code null} if invalid
     */
    private ParsedChoice parseChoice(final Object rawChoice) {
        if (!(rawChoice instanceof Map<?, ?> choiceMap)) {
            return null;
        }
        String text = asTrimmedString(choiceMap.get("text"));
        if (text == null || text.isEmpty() || text.length() > MAX_CHOICE_TEXT_LENGTH) {
            return null;
        }
        boolean correct = Boolean.TRUE.equals(choiceMap.get("correct"));
        return new ParsedChoice(text, correct);
    }

    /**
     * Coerces a raw JSON value to a trimmed string, or {@code null} for anything other than a
     * {@link String} (mirrors the null-safe coercion helpers of {@code VoteActionService}).
     *
     * @param rawValue the raw value
     * @return the trimmed string, or {@code null}
     */
    private static String asTrimmedString(final Object rawValue) {
        return rawValue instanceof String s ? s.trim() : null;
    }

    // -------------------------------------------------------------------------
    // Shared helpers (calqued on VoteActionService)
    // -------------------------------------------------------------------------

    /**
     * Loads and pessimistically locks the active session referenced by {@code data.sessionId},
     * scoped to this board and the caller's tenant — the shared entry guard for
     * {@code quiz:answer}/{@code quiz:next}/{@code quiz:reveal}/{@code quiz:stop}. Returns
     * {@code null} (caller drops) when the id is malformed, unknown, cross-board/tenant, or the
     * session is no longer active.
     *
     * @param boardId   the board UUID
     * @param data      the incoming payload
     * @param principal the emitting principal
     * @return the locked active session, or {@code null} to drop the action
     */
    private QuizSession lockActiveSession(
            final UUID boardId, final Map<String, Object> data, final StompPrincipal principal) {
        UUID sessionId = parseUuid(data.get("sessionId"));
        if (sessionId == null) {
            return null;
        }
        Optional<QuizSession> found = quizSessionRepository.findForUpdate(sessionId, boardId, principal.tenantId());
        if (found.isEmpty() || found.get().getStatus() != QuizStatus.ACTIVE) {
            LOG.debug("quiz action dropped (missing, cross-board/tenant, or closed): session={} board={}",
                    sessionId, boardId);
            return null;
        }
        return found.get();
    }

    /**
     * Broadcasts an already-built session view to the whole room under {@code wireType}, emitter
     * included. The {@link QuizSessionResponse} is converted to a field map so it becomes the
     * {@code data} of the {@link BroadcastCanvasMessage} — the shape the frontend deserialises.
     *
     * @param boardId   the board UUID
     * @param principal the emitting principal
     * @param wireType  the outbound wire type
     * @param dto       the already-masked-or-demasked session view to broadcast
     */
    private void broadcast(
            final UUID boardId, final StompPrincipal principal, final String wireType, final QuizSessionResponse dto) {
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = objectMapper.convertValue(dto, Map.class);
        BroadcastCanvasMessage msg = new BroadcastCanvasMessage(
                wireType, boardId.toString(), principal.userId().toString(), dataMap);
        messagingTemplate.convertAndSend(BOARD_TOPIC_PREFIX + boardId, msg);
    }

    /**
     * Returns whether the given user may manage the quiz on the board (OWNER or EDITOR) — the guard
     * for {@code quiz:start}/{@code quiz:next}/{@code quiz:reveal}/{@code quiz:stop}. A missing
     * membership defaults to non-manager.
     *
     * @param boardId the board UUID
     * @param userId  the user's {@code public.users.id}
     * @return {@code true} if the user is OWNER or EDITOR
     */
    private boolean canManage(final UUID boardId, final Long userId) {
        return boardMemberRepository.findByIdBoardIdAndIdUserId(boardId, userId)
                .map(m -> m.getRole() == BoardRole.OWNER || m.getRole() == BoardRole.EDITOR)
                .orElse(false);
    }

    /**
     * Coerces the incoming polymorphic {@code data} to a field-accessible map, or an empty map for
     * a non-map value (mirrors {@code VoteActionService#asMap}).
     *
     * @param rawData the raw envelope data
     * @return a string-keyed map, or an empty map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(final Object rawData) {
        return rawData instanceof Map<?, ?> ? (Map<String, Object>) rawData : Map.of();
    }

    /**
     * Parses a raw id value into a {@link UUID}, returning {@code null} for a missing or malformed
     * value so the caller can silently drop the action.
     *
     * @param rawId the raw value
     * @return the parsed UUID, or {@code null}
     */
    private static UUID parseUuid(final Object rawId) {
        if (!(rawId instanceof String s)) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
