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
import fr.pivot.collaboratif.session.qa.dto.QuestionAddedEvent;
import fr.pivot.collaboratif.session.qa.dto.QuestionAnsweredEvent;
import fr.pivot.collaboratif.session.qa.dto.QuestionUpvotedEvent;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for the Q&amp;A activity type (US19.3.5) — participant question submission,
 * one-upvote-per-participant, facilitator answered-marking, and an upvote-descending list read.
 *
 * <p>Unlike POLL, Q&amp;A needs no creation-time configuration: questions are authored live, so
 * this service exposes no {@code initializeFromConfig} and is never called from {@code
 * ModuleSessionService#create}. It broadcasts every mutation on the shared session topic
 * ({@link SessionDestinations#topicFor}) exactly as POLL/WORDCLOUD do.
 */
@Service
public class QaActivityService {

    private static final int MAX_TEXT_LENGTH = 500;

    private final SessionQaQuestionRepository questionRepository;
    private final SessionQaUpvoteRepository upvoteRepository;
    private final ParticipantRepository participantRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Creates the service with its required dependencies.
     *
     * @param questionRepository    repository for questions
     * @param upvoteRepository      repository for per-participant upvotes
     * @param participantRepository repository used to resolve author display names
     * @param messagingTemplate     STOMP broadcaster
     */
    public QaActivityService(
            final SessionQaQuestionRepository questionRepository,
            final SessionQaUpvoteRepository upvoteRepository,
            final ParticipantRepository participantRepository,
            final SimpMessagingTemplate messagingTemplate) {
        this.questionRepository = questionRepository;
        this.upvoteRepository = upvoteRepository;
        this.participantRepository = participantRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Returns whether this service handles the given session type.
     *
     * @param type the session type
     * @return {@code true} for {@link SessionType#QA}
     */
    public boolean supports(final SessionType type) {
        return type == SessionType.QA;
    }

    /**
     * Submits a question from a participant (US19.3.5) and broadcasts {@code QUESTION_ADDED}.
     *
     * @param session       the LIVE Q&amp;A session
     * @param participantId the authoring participant's id
     * @param rawText       the raw question text
     * @param anonymous     whether to withhold the author's name from other participants
     * @throws InvalidSessionStatusException if the session is not a LIVE Q&amp;A
     * @throws SessionValidationException    if the text is empty or exceeds 500 characters
     */
    @Transactional
    public void submitQuestion(
            final Session session, final UUID participantId, final String rawText, final boolean anonymous) {
        requireLiveQa(session);
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            throw new SessionValidationException("INVALID_QUESTION", "Question must be 1 to 500 characters");
        }

        SessionQaQuestion question = questionRepository.save(
                new SessionQaQuestion(session.getId(), participantId, text, anonymous, Instant.now()));

        String authorName = anonymous ? null : displayNameOf(participantId);
        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(session.getId()),
                new QuestionAddedEvent(session.getId(), new QaQuestionDto(
                        question.getId(), question.getText(), authorName, question.isAnonymous(),
                        question.isAnswered(), 0L, question.getCreatedAt())));
    }

    /**
     * Records a participant's upvote on a question (US19.3.5) and broadcasts {@code
     * QUESTION_UPVOTED} with the new tally.
     *
     * @param session       the LIVE Q&amp;A session
     * @param participantId the upvoting participant's id
     * @param questionId    the question to upvote
     * @throws InvalidSessionStatusException if the session is not a LIVE Q&amp;A
     * @throws SessionNotFoundException      if the question does not belong to this session
     * @throws SessionConflictException      if the participant has already upvoted this question
     */
    @Transactional
    public void upvote(final Session session, final UUID participantId, final UUID questionId) {
        requireLiveQa(session);
        SessionQaQuestion question = questionRepository.findByIdAndSessionId(questionId, session.getId())
                .orElseThrow(SessionNotFoundException::new);
        if (upvoteRepository.existsByQuestionIdAndParticipantId(question.getId(), participantId)) {
            throw new SessionConflictException("ALREADY_UPVOTED", "Participant has already upvoted this question");
        }
        upvoteRepository.save(new SessionQaUpvote(question.getId(), participantId, Instant.now()));

        long upvotes = upvoteRepository.countByQuestionId(question.getId());
        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(session.getId()),
                new QuestionUpvotedEvent(session.getId(), question.getId(), upvotes));
    }

    /**
     * Marks a question as answered — facilitator action (US19.3.5) — and broadcasts {@code
     * QUESTION_ANSWERED}.
     *
     * @param sessionId  the owning session's UUID
     * @param questionId the question to mark answered
     * @throws SessionNotFoundException if the question does not belong to this session
     */
    @Transactional
    public void markAnswered(final UUID sessionId, final UUID questionId) {
        SessionQaQuestion question = questionRepository.findByIdAndSessionId(questionId, sessionId)
                .orElseThrow(SessionNotFoundException::new);
        question.markAnswered();
        questionRepository.save(question);
        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(sessionId), new QuestionAnsweredEvent(sessionId, question.getId()));
    }

    /**
     * Lists a session's questions, most-upvoted first with creation order as the stable
     * tie-breaker (US19.3.5).
     *
     * @param sessionId the owning session's UUID
     * @return the questions, upvotes descending then oldest first
     */
    @Transactional(readOnly = true)
    public List<QaQuestionDto> getQuestions(final UUID sessionId) {
        List<SessionQaQuestion> questions = questionRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId);
        if (questions.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> upvotesByQuestion = tallyUpvotes(questions);
        Map<UUID, String> nameByParticipant = resolveAuthorNames(questions);

        return questions.stream()
                .map(q -> new QaQuestionDto(
                        q.getId(), q.getText(),
                        q.isAnonymous() ? null : nameByParticipant.get(q.getParticipantId()),
                        q.isAnonymous(), q.isAnswered(),
                        upvotesByQuestion.getOrDefault(q.getId(), 0L), q.getCreatedAt()))
                .sorted(Comparator
                        .comparingLong(QaQuestionDto::upvotes).reversed()
                        .thenComparing(QaQuestionDto::createdAt))
                .toList();
    }

    private Map<UUID, Long> tallyUpvotes(final List<SessionQaQuestion> questions) {
        List<UUID> questionIds = questions.stream().map(SessionQaQuestion::getId).toList();
        Map<UUID, Long> tally = new HashMap<>();
        for (SessionQaUpvote upvote : upvoteRepository.findAllByQuestionIdIn(questionIds)) {
            tally.merge(upvote.getQuestionId(), 1L, Long::sum);
        }
        return tally;
    }

    private Map<UUID, String> resolveAuthorNames(final List<SessionQaQuestion> questions) {
        List<UUID> participantIds = questions.stream()
                .filter(q -> !q.isAnonymous())
                .map(SessionQaQuestion::getParticipantId)
                .distinct()
                .toList();
        Map<UUID, String> names = new HashMap<>();
        for (Participant participant : participantRepository.findAllById(participantIds)) {
            names.put(participant.getId(), participant.getDisplayName());
        }
        return names;
    }

    private String displayNameOf(final UUID participantId) {
        return participantRepository.findById(participantId)
                .map(Participant::getDisplayName)
                .orElse(null);
    }

    private void requireLiveQa(final Session session) {
        if (session.getType() != SessionType.QA || session.getStatus() != SessionStatus.LIVE) {
            throw new InvalidSessionStatusException("Session is not a LIVE Q&A");
        }
    }
}
