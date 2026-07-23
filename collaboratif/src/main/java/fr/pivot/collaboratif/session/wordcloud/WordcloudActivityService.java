package fr.pivot.collaboratif.session.wordcloud;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import fr.pivot.collaboratif.session.wordcloud.dto.WordAddedEvent;
import fr.pivot.collaboratif.session.wordcloud.dto.WordEntryDto;
import fr.pivot.collaboratif.session.wordcloud.dto.WordRemovedEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for the WORDCLOUD activity type (US19.3.3) — normalized/aggregated word
 * submission with a per-participant limit and tenant-level moderation blocklist.
 */
@Service
public class WordcloudActivityService {

    private static final int DEFAULT_MAX_WORDS_PER_PARTICIPANT = 3;
    private static final int MAX_WORD_LENGTH = 30;

    private final SessionWordcloudEntryRepository entryRepository;
    private final SessionWordcloudSubmissionRepository submissionRepository;
    private final TenantWordBlocklistRepository blocklistRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service with its required dependencies.
     *
     * @param entryRepository      repository for aggregated word entries
     * @param submissionRepository repository for per-participant submission tracking
     * @param blocklistRepository  repository for the tenant-level blocklist
     * @param messagingTemplate    STOMP broadcaster
     * @param objectMapper         JSON deserializer for the session's {@code config}
     */
    public WordcloudActivityService(
            final SessionWordcloudEntryRepository entryRepository,
            final SessionWordcloudSubmissionRepository submissionRepository,
            final TenantWordBlocklistRepository blocklistRepository,
            final SimpMessagingTemplate messagingTemplate,
            final ObjectMapper objectMapper) {
        this.entryRepository = entryRepository;
        this.submissionRepository = submissionRepository;
        this.blocklistRepository = blocklistRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns whether this service handles the given session type.
     *
     * @param type the session type
     * @return {@code true} for {@link SessionType#WORDCLOUD}
     */
    public boolean supports(final SessionType type) {
        return type == SessionType.WORDCLOUD;
    }

    /**
     * Submits a word from a participant (US19.3.3) — normalizes, checks the tenant blocklist and
     * the participant's remaining quota, then aggregates.
     *
     * @param session       the LIVE WORDCLOUD session
     * @param participantId the submitting participant's id
     * @param rawWord       the raw submitted word
     * @throws SessionValidationException if the word is empty/too long after normalization
     * @throws SessionConflictException   if the word is blocklisted or the quota is exhausted
     */
    @Transactional
    public void submitWord(final Session session, final UUID participantId, final String rawWord) {
        requireLiveWordcloud(session);
        String word = normalize(rawWord);
        if (word.isEmpty() || word.length() > MAX_WORD_LENGTH) {
            throw new SessionValidationException("INVALID_WORD", "Word must be 1 to 30 characters");
        }
        if (blocklistRepository.existsByTenantIdAndWord(session.getTenantId(), word)) {
            throw new SessionConflictException("WORD_BLOCKED", "Word is blocklisted for this tenant");
        }

        long alreadySubmitted =
                submissionRepository.countBySessionIdAndParticipantId(session.getId(), participantId);
        if (alreadySubmitted >= readMaxWordsPerParticipant(session)) {
            throw new SessionConflictException("WORD_LIMIT_REACHED", "Participant word limit reached");
        }

        Instant now = Instant.now();
        submissionRepository.save(new SessionWordcloudSubmission(session.getId(), participantId, word, now));
        SessionWordcloudEntry entry = entryRepository.findBySessionIdAndWord(session.getId(), word)
                .map(existing -> {
                    existing.incrementFrequency();
                    return existing;
                })
                .orElseGet(() -> new SessionWordcloudEntry(session.getId(), word));
        entry = entryRepository.save(entry);

        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(session.getId()),
                new WordAddedEvent(session.getId(), new WordEntryDto(entry.getWord(), entry.getFrequency())));
    }

    /**
     * Removes a word entirely from the cloud — facilitator moderation (US19.3.3).
     *
     * @param sessionId the owning session's UUID
     * @param rawWord   the word to remove
     */
    @Transactional
    public void removeWord(final UUID sessionId, final String rawWord) {
        String word = normalize(rawWord);
        entryRepository.deleteBySessionIdAndWord(sessionId, word);
        submissionRepository.deleteBySessionIdAndWord(sessionId, word);
        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(sessionId), new WordRemovedEvent(sessionId, word));
    }

    /**
     * Lists the current aggregated word cloud, most frequent first.
     *
     * @param sessionId the owning session's UUID
     * @return the aggregated words
     */
    @Transactional(readOnly = true)
    public List<WordEntryDto> getWords(final UUID sessionId) {
        return entryRepository.findAllBySessionIdOrderByFrequencyDesc(sessionId).stream()
                .map(entry -> new WordEntryDto(entry.getWord(), entry.getFrequency()))
                .toList();
    }

    private void requireLiveWordcloud(final Session session) {
        if (session.getType() != SessionType.WORDCLOUD || session.getStatus() != SessionStatus.LIVE) {
            throw new InvalidSessionStatusException("Session is not a LIVE wordcloud");
        }
    }

    private int readMaxWordsPerParticipant(final Session session) {
        try {
            JsonNode config = objectMapper.readTree(session.getConfig());
            JsonNode max = config.get("maxWordsPerParticipant");
            return max != null ? max.asInt(DEFAULT_MAX_WORDS_PER_PARTICIPANT) : DEFAULT_MAX_WORDS_PER_PARTICIPANT;
        } catch (Exception e) {
            return DEFAULT_MAX_WORDS_PER_PARTICIPANT;
        }
    }

    private String normalize(final String rawWord) {
        return rawWord == null ? "" : rawWord.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
