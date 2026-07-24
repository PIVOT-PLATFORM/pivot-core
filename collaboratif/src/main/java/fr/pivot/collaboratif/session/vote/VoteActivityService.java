package fr.pivot.collaboratif.session.vote;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.vote.dto.MatrixOptionResult;
import fr.pivot.collaboratif.session.vote.dto.SubmitBallotRequest;
import fr.pivot.collaboratif.session.vote.dto.VoteClosedEvent;
import fr.pivot.collaboratif.session.vote.dto.VoteResultsDto;
import fr.pivot.collaboratif.session.vote.dto.VoteSubmittedEvent;
import fr.pivot.collaboratif.session.vote.dto.WeightedOptionResult;
import fr.pivot.collaboratif.session.ws.SessionDestinations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for the VOTE activity type (US19.3.6) — a structured, one-shot, auditable
 * decision (unlike POLL's re-votable sondage). Supports FIST_TO_FIVE (0-5 rating of one proposal,
 * with a consensus level and a veto alert) and WEIGHTED (distribute a points budget across
 * options). Ballots stay secret until the facilitator closes the vote.
 *
 * <p>The vote mode and its parameters are read lazily from the session's {@code config} (mirroring
 * WORDCLOUD), defaulting to {@link VoteType#FIST_TO_FIVE} — so a session created with an empty
 * config is still usable.
 */
@Service
public class VoteActivityService {

    private static final int MIN_RATING = 0;
    private static final int MAX_RATING = 5;
    private static final int DEFAULT_POINTS = 5;
    private static final double STRONG_THRESHOLD = 4.0;
    private static final double MODERATE_THRESHOLD = 3.0;

    private final SessionVoteBallotRepository ballotRepository;
    private final SessionVoteStateRepository stateRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service with its required dependencies.
     *
     * @param ballotRepository  repository for ballots
     * @param stateRepository   repository for the open/closed flag
     * @param messagingTemplate STOMP broadcaster
     * @param objectMapper      JSON (de)serializer for config and ballot payloads
     */
    public VoteActivityService(
            final SessionVoteBallotRepository ballotRepository,
            final SessionVoteStateRepository stateRepository,
            final SimpMessagingTemplate messagingTemplate,
            final ObjectMapper objectMapper) {
        this.ballotRepository = ballotRepository;
        this.stateRepository = stateRepository;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns whether this service handles the given session type.
     *
     * @param type the session type
     * @return {@code true} for {@link SessionType#VOTE}
     */
    public boolean supports(final SessionType type) {
        return type == SessionType.VOTE;
    }

    /**
     * Casts a participant's ballot (US19.3.6) — one per participant, never overwritten.
     *
     * @param session       the LIVE VOTE session
     * @param participantId the voting participant's id
     * @param request       the ballot (the relevant field depends on the vote type)
     * @throws InvalidSessionStatusException if the session is not a LIVE VOTE
     * @throws SessionConflictException      if the vote is closed, or the participant already voted
     * @throws SessionValidationException    if the ballot is invalid for the vote type
     */
    @Transactional
    public void submitBallot(final Session session, final UUID participantId, final SubmitBallotRequest request) {
        requireLiveVote(session);
        if (isClosed(session.getId())) {
            throw new SessionConflictException("VOTE_CLOSED", "The vote is closed");
        }
        if (ballotRepository.existsBySessionIdAndParticipantId(session.getId(), participantId)) {
            throw new SessionConflictException("ALREADY_VOTED", "Participant has already voted");
        }
        String payload = validateAndSerialize(session, request);
        ballotRepository.save(new SessionVoteBallot(session.getId(), participantId, payload, Instant.now()));

        long count = ballotRepository.countBySessionId(session.getId());
        broadcast(session.getId(), new VoteSubmittedEvent(session.getId(), count));
    }

    /**
     * Closes the vote and broadcasts the revealed results (US19.3.6) — facilitator action.
     *
     * @param session the VOTE session to close
     */
    @Transactional
    public void close(final Session session) {
        SessionVoteState state = stateRepository.findBySessionId(session.getId())
                .orElseGet(() -> new SessionVoteState(session.getId(), false));
        state.setClosed(true);
        stateRepository.save(state);
        broadcast(session.getId(), new VoteClosedEvent(session.getId(), getResults(session)));
    }

    /**
     * Computes the current results (US19.3.6) — tallies are withheld until the vote is closed.
     *
     * @param session the VOTE session
     * @return the results (open-state, no tallies, if not yet closed)
     */
    @Transactional(readOnly = true)
    public VoteResultsDto getResults(final Session session) {
        VoteType voteType = readVoteType(session);
        long count = ballotRepository.countBySessionId(session.getId());
        if (!isClosed(session.getId())) {
            return VoteResultsDto.open(voteType, count);
        }
        List<SessionVoteBallot> ballots = ballotRepository.findAllBySessionId(session.getId());
        return switch (voteType) {
            case WEIGHTED -> weightedResults(session, ballots, count);
            case MATRIX -> matrixResults(session, ballots, count);
            default -> fistResults(ballots, count);
        };
    }

    // --- results --------------------------------------------------------------------------------

    private VoteResultsDto fistResults(final List<SessionVoteBallot> ballots, final long count) {
        boolean veto = false;
        int sum = 0;
        int n = 0;
        for (SessionVoteBallot ballot : ballots) {
            int value = readInt(ballot.getPayload(), "value");
            if (value == 0) {
                veto = true;
            }
            sum += value;
            n++;
        }
        double average = n == 0 ? 0.0 : (double) sum / n;
        return new VoteResultsDto(
                VoteType.FIST_TO_FIVE, true, count, average, consensusLevel(average), veto, List.of(), List.of());
    }

    private VoteResultsDto weightedResults(
            final Session session, final List<SessionVoteBallot> ballots, final long count) {
        List<String> options = readWeightedOptions(session);
        int[] totals = new int[options.size()];
        for (SessionVoteBallot ballot : ballots) {
            for (Map.Entry<Integer, Integer> allocation : readAllocations(ballot.getPayload()).entrySet()) {
                int index = allocation.getKey();
                if (index >= 0 && index < totals.length) {
                    totals[index] += allocation.getValue();
                }
            }
        }
        List<WeightedOptionResult> results = new ArrayList<>(options.size());
        for (int i = 0; i < options.size(); i++) {
            results.add(new WeightedOptionResult(i, options.get(i), totals[i]));
        }
        return new VoteResultsDto(VoteType.WEIGHTED, true, count, null, null, false, results, List.of());
    }

    private VoteResultsDto matrixResults(
            final Session session, final List<SessionVoteBallot> ballots, final long count) {
        List<String> options = readWeightedOptions(session);
        List<Integer> weights = readCriteriaWeights(session);
        int optionCount = options.size();
        int criterionCount = weights.size();

        // Sum each cell [option][criterion] across ballots, then mean, then weight and sum per option.
        long[][] cellSums = new long[optionCount][criterionCount];
        for (SessionVoteBallot ballot : ballots) {
            List<List<Integer>> scores = readScores(ballot.getPayload());
            for (int i = 0; i < optionCount && i < scores.size(); i++) {
                List<Integer> row = scores.get(i);
                for (int j = 0; j < criterionCount && j < row.size(); j++) {
                    cellSums[i][j] += row.get(j);
                }
            }
        }

        int ballotCount = ballots.size();
        List<MatrixOptionResult> results = new ArrayList<>(optionCount);
        for (int i = 0; i < optionCount; i++) {
            double weighted = 0.0;
            for (int j = 0; j < criterionCount; j++) {
                double mean = ballotCount == 0 ? 0.0 : (double) cellSums[i][j] / ballotCount;
                weighted += weights.get(j) * mean;
            }
            results.add(new MatrixOptionResult(i, options.get(i), weighted));
        }
        results.sort(Comparator.comparingDouble(MatrixOptionResult::score).reversed());
        return new VoteResultsDto(VoteType.MATRIX, true, count, null, null, false, List.of(), results);
    }

    private String consensusLevel(final double average) {
        if (average >= STRONG_THRESHOLD) {
            return "STRONG";
        }
        return average >= MODERATE_THRESHOLD ? "MODERATE" : "WEAK";
    }

    // --- ballot validation ----------------------------------------------------------------------

    private String validateAndSerialize(final Session session, final SubmitBallotRequest request) {
        return switch (readVoteType(session)) {
            case WEIGHTED -> serializeWeighted(session, request);
            case MATRIX -> serializeMatrix(session, request);
            default -> serializeFist(request);
        };
    }

    private String serializeFist(final SubmitBallotRequest request) {
        Integer value = request.value();
        if (value == null || value < MIN_RATING || value > MAX_RATING) {
            throw new SessionValidationException("INVALID_BALLOT", "Fist-to-five rating must be 0 to 5");
        }
        return write(Map.of("value", value));
    }

    private String serializeWeighted(final Session session, final SubmitBallotRequest request) {
        Map<String, Integer> allocations = request.allocations();
        if (allocations == null || allocations.isEmpty()) {
            throw new SessionValidationException("INVALID_BALLOT", "Weighted ballot needs allocations");
        }
        int optionCount = readWeightedOptions(session).size();
        int budget = readPointsPerParticipant(session);
        int total = 0;
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : allocations.entrySet()) {
            int index = parseIndex(entry.getKey(), optionCount);
            int points = entry.getValue() == null ? -1 : entry.getValue();
            if (points < 0) {
                throw new SessionValidationException("INVALID_BALLOT", "Points cannot be negative");
            }
            total += points;
            normalized.put(String.valueOf(index), points);
        }
        if (total != budget) {
            throw new SessionValidationException(
                    "INVALID_BALLOT", "Allocated points must sum to " + budget);
        }
        return write(Map.of("allocations", normalized));
    }

    private String serializeMatrix(final Session session, final SubmitBallotRequest request) {
        List<List<Integer>> scores = request.scores();
        int optionCount = readWeightedOptions(session).size();
        int criterionCount = readCriteriaWeights(session).size();
        if (scores == null || scores.size() != optionCount) {
            throw new SessionValidationException("INVALID_BALLOT", "Matrix must score every option");
        }
        int maxScore = readMaxScore(session);
        for (List<Integer> row : scores) {
            if (row == null || row.size() != criterionCount) {
                throw new SessionValidationException("INVALID_BALLOT", "Each option must score every criterion");
            }
            for (Integer cell : row) {
                if (cell == null || cell < 0 || cell > maxScore) {
                    throw new SessionValidationException(
                            "INVALID_BALLOT", "Each score must be 0 to " + maxScore);
                }
            }
        }
        return write(Map.of("scores", scores));
    }

    private int parseIndex(final String rawKey, final int optionCount) {
        try {
            int index = Integer.parseInt(rawKey);
            if (index < 0 || index >= optionCount) {
                throw new SessionValidationException("INVALID_BALLOT", "Unknown option index");
            }
            return index;
        } catch (NumberFormatException e) {
            throw new SessionValidationException("INVALID_BALLOT", "Option index must be an integer");
        }
    }

    // --- config / payload helpers ---------------------------------------------------------------

    private VoteType readVoteType(final Session session) {
        JsonNode node = config(session).get("voteType");
        if (node == null) {
            return VoteType.FIST_TO_FIVE;
        }
        try {
            return VoteType.valueOf(node.asText().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VoteType.FIST_TO_FIVE;
        }
    }

    private List<String> readWeightedOptions(final Session session) {
        JsonNode options = config(session).get("options");
        if (options == null || !options.isArray()) {
            throw new SessionValidationException("INVALID_BALLOT", "WEIGHTED vote has no options configured");
        }
        List<String> labels = new ArrayList<>(options.size());
        for (JsonNode option : options) {
            labels.add(option.asText());
        }
        return labels;
    }

    private int readPointsPerParticipant(final Session session) {
        JsonNode points = config(session).get("pointsPerParticipant");
        return points != null ? points.asInt(DEFAULT_POINTS) : DEFAULT_POINTS;
    }

    private List<Integer> readCriteriaWeights(final Session session) {
        JsonNode criteria = config(session).get("criteria");
        if (criteria == null || !criteria.isArray() || criteria.isEmpty()) {
            throw new SessionValidationException("INVALID_BALLOT", "MATRIX vote has no criteria configured");
        }
        List<Integer> weights = new ArrayList<>(criteria.size());
        for (JsonNode criterion : criteria) {
            JsonNode weight = criterion.get("weight");
            weights.add(weight != null ? weight.asInt(1) : 1);
        }
        return weights;
    }

    private int readMaxScore(final Session session) {
        JsonNode max = config(session).get("maxScore");
        return max != null ? max.asInt(MAX_RATING) : MAX_RATING;
    }

    private List<List<Integer>> readScores(final String payload) {
        List<List<Integer>> result = new ArrayList<>();
        try {
            JsonNode scores = objectMapper.readTree(payload).get("scores");
            if (scores == null || !scores.isArray()) {
                return result;
            }
            for (JsonNode row : scores) {
                List<Integer> cells = new ArrayList<>();
                for (JsonNode cell : row) {
                    cells.add(cell.asInt(0));
                }
                result.add(cells);
            }
        } catch (Exception e) {
            return result;
        }
        return result;
    }

    private JsonNode config(final Session session) {
        try {
            return objectMapper.readTree(session.getConfig() == null ? "{}" : session.getConfig());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read session config", e);
        }
    }

    private int readInt(final String payload, final String field) {
        try {
            JsonNode node = objectMapper.readTree(payload).get(field);
            return node == null ? 0 : node.asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<Integer, Integer> readAllocations(final String payload) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        try {
            JsonNode allocations = objectMapper.readTree(payload).get("allocations");
            if (allocations == null) {
                return result;
            }
            allocations.properties().forEach(entry ->
                    result.put(Integer.parseInt(entry.getKey()), entry.getValue().asInt(0)));
        } catch (Exception e) {
            return result;
        }
        return result;
    }

    private String write(final Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize vote payload", e);
        }
    }

    private boolean isClosed(final UUID sessionId) {
        return stateRepository.findBySessionId(sessionId)
                .map(SessionVoteState::isClosed)
                .orElse(false);
    }

    private void requireLiveVote(final Session session) {
        if (session.getType() != SessionType.VOTE || session.getStatus() != SessionStatus.LIVE) {
            throw new InvalidSessionStatusException("Session is not a LIVE vote");
        }
    }

    private void broadcast(final UUID sessionId, final Object event) {
        messagingTemplate.convertAndSend(SessionDestinations.topicFor(sessionId), event);
    }
}
