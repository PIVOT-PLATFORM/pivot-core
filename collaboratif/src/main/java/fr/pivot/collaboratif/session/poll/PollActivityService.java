package fr.pivot.collaboratif.session.poll;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.poll.dto.PollOptionResult;
import fr.pivot.collaboratif.session.poll.dto.PollUpdatedEvent;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for the POLL activity type (US19.3.2) — option setup at session creation, live
 * voting with upsert-on-revote, and facilitator hide/show-results.
 */
@Service
public class PollActivityService {

    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 8;

    private final SessionPollOptionRepository optionRepository;
    private final SessionPollVoteRepository voteRepository;
    private final SessionPollStateRepository stateRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service with its required dependencies.
     *
     * @param optionRepository  repository for poll options
     * @param voteRepository    repository for poll votes
     * @param stateRepository   repository for the hide/show-results flag
     * @param messagingTemplate STOMP broadcaster
     * @param objectMapper      JSON (de)serializer for the session's {@code config}/vote payloads
     */
    public PollActivityService(
            final SessionPollOptionRepository optionRepository,
            final SessionPollVoteRepository voteRepository,
            final SessionPollStateRepository stateRepository,
            final SimpMessagingTemplate messagingTemplate,
            final ObjectMapper objectMapper) {
        this.optionRepository = optionRepository;
        this.voteRepository = voteRepository;
        this.stateRepository = stateRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns whether this service handles the given session type.
     *
     * @param type the session type
     * @return {@code true} for {@link SessionType#POLL}
     */
    public boolean supports(final SessionType type) {
        return type == SessionType.POLL;
    }

    /**
     * Materializes {@link SessionPollOption} rows from a session's {@code config} at creation
     * time (US19.3.2): {@code { question, options: [2..8 strings], allowMultiple? }}.
     *
     * @param sessionId the owning session's UUID
     * @param config    the raw config JSON node
     * @throws SessionValidationException if {@code options} is missing or has the wrong size
     */
    @Transactional
    public void initializeFromConfig(final UUID sessionId, final JsonNode config) {
        JsonNode options = config.get("options");
        if (options == null || !options.isArray()
                || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw new SessionValidationException("INVALID_POLL_VOTE",
                    "POLL config must carry 2 to 8 options");
        }
        int order = 0;
        for (JsonNode option : options) {
            optionRepository.save(new SessionPollOption(sessionId, option.asText(), order++));
        }
    }

    /**
     * Casts or replaces a participant's vote (US19.3.2) — upsert, one active vote per participant.
     *
     * @param session       the LIVE POLL session
     * @param participantId the voting participant's id
     * @param optionIds     the selected option ids (1 element unless {@code allowMultiple})
     */
    @Transactional
    public void vote(final Session session, final UUID participantId, final List<UUID> optionIds) {
        requireLivePoll(session);
        List<SessionPollOption> options =
                optionRepository.findAllBySessionIdOrderBySortOrderAsc(session.getId());
        boolean allowMultiple = readAllowMultiple(session);
        validateSelection(options, optionIds, allowMultiple);

        String optionIdsJson = writeOptionIds(optionIds);
        Instant now = Instant.now();
        voteRepository.findBySessionIdAndParticipantId(session.getId(), participantId)
                .ifPresentOrElse(
                        existing -> existing.replace(optionIdsJson, now),
                        () -> voteRepository.save(new SessionPollVote(
                                session.getId(), participantId, optionIdsJson, now)));

        broadcastResults(session.getId());
    }

    /**
     * Hides results from participants (US19.3.2) — the facilitator's own view is unaffected
     * (a separate, non-broadcast read path, US19.4.1).
     *
     * @param sessionId the owning session's UUID
     */
    @Transactional
    public void hideResults(final UUID sessionId) {
        setResultsHidden(sessionId, true);
    }

    /**
     * Shows results to participants again (US19.3.2).
     *
     * @param sessionId the owning session's UUID
     */
    @Transactional
    public void showResults(final UUID sessionId) {
        setResultsHidden(sessionId, false);
    }

    /**
     * Computes the current per-option tallies.
     *
     * @param sessionId    the owning session's UUID
     * @param forFacilitator when {@code true}, counts/percentages are always included regardless
     *                       of the hidden flag (the facilitator's own view, US19.4.1); when
     *                       {@code false}, they are omitted if results are currently hidden
     * @return the per-option results, in display order
     */
    @Transactional(readOnly = true)
    public List<PollOptionResult> getResults(final UUID sessionId, final boolean forFacilitator) {
        List<SessionPollOption> options =
                optionRepository.findAllBySessionIdOrderBySortOrderAsc(sessionId);
        boolean hidden = !forFacilitator && isResultsHidden(sessionId);
        Map<UUID, Integer> tally = tally(sessionId, options);
        int total = tally.values().stream().mapToInt(Integer::intValue).sum();

        List<PollOptionResult> results = new ArrayList<>(options.size());
        for (SessionPollOption option : options) {
            if (hidden) {
                results.add(new PollOptionResult(option.getId(), option.getLabel(), null, null));
            } else {
                int count = tally.getOrDefault(option.getId(), 0);
                double percent = total == 0 ? 0.0 : (count * 100.0) / total;
                results.add(new PollOptionResult(option.getId(), option.getLabel(), count, percent));
            }
        }
        return results;
    }

    private void setResultsHidden(final UUID sessionId, final boolean hidden) {
        SessionPollState state = stateRepository.findBySessionId(sessionId)
                .orElseGet(() -> new SessionPollState(sessionId, false));
        state.setResultsHidden(hidden);
        stateRepository.save(state);
        broadcastResults(sessionId);
    }

    private boolean isResultsHidden(final UUID sessionId) {
        return stateRepository.findBySessionId(sessionId)
                .map(SessionPollState::isResultsHidden)
                .orElse(false);
    }

    private void broadcastResults(final UUID sessionId) {
        List<PollOptionResult> results = getResults(sessionId, false);
        messagingTemplate.convertAndSend(
                SessionDestinations.topicFor(sessionId), new PollUpdatedEvent(sessionId, results));
    }

    private Map<UUID, Integer> tally(final UUID sessionId, final List<SessionPollOption> options) {
        Map<UUID, Integer> tally = new HashMap<>();
        for (SessionPollOption option : options) {
            tally.put(option.getId(), 0);
        }
        for (SessionPollVote vote : voteRepository.findAllBySessionId(sessionId)) {
            for (UUID optionId : readOptionIds(vote.getOptionIds())) {
                tally.merge(optionId, 1, Integer::sum);
            }
        }
        return tally;
    }

    private void requireLivePoll(final Session session) {
        if (session.getType() != SessionType.POLL || session.getStatus() != SessionStatus.LIVE) {
            throw new InvalidSessionStatusException("Session is not a LIVE poll");
        }
    }

    private boolean readAllowMultiple(final Session session) {
        try {
            JsonNode config = objectMapper.readTree(session.getConfig());
            JsonNode allowMultiple = config.get("allowMultiple");
            return allowMultiple != null && allowMultiple.asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private void validateSelection(
            final List<SessionPollOption> options, final List<UUID> optionIds, final boolean allowMultiple) {
        if (optionIds.isEmpty() || (!allowMultiple && optionIds.size() > 1)) {
            throw new SessionValidationException("INVALID_POLL_VOTE", "Invalid number of selected options");
        }
        List<UUID> validIds = options.stream().map(SessionPollOption::getId).toList();
        if (!validIds.containsAll(optionIds)) {
            throw new SessionValidationException("INVALID_POLL_VOTE", "Unknown option id");
        }
    }

    private String writeOptionIds(final List<UUID> optionIds) {
        try {
            return objectMapper.writeValueAsString(optionIds);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize poll vote option ids", e);
        }
    }

    private List<UUID> readOptionIds(final String json) {
        try {
            List<String> raw = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            return raw.stream().map(UUID::fromString).sorted(Comparator.naturalOrder()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
