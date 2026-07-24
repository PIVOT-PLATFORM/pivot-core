package fr.pivot.collaboratif.session.vote;

import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.vote.dto.SubmitBallotRequest;
import fr.pivot.collaboratif.session.vote.dto.VoteResultsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoteActivityServiceTest {

    private static final String WEIGHTED_CONFIG =
            "{\"voteType\":\"WEIGHTED\",\"options\":[\"A\",\"B\"],\"pointsPerParticipant\":5}";

    @Mock
    private SessionVoteBallotRepository ballotRepository;
    @Mock
    private SessionVoteStateRepository stateRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private VoteActivityService service;

    @BeforeEach
    void setUp() {
        service = new VoteActivityService(ballotRepository, stateRepository, messagingTemplate, new ObjectMapper());
    }

    private Session liveVote(final String config) {
        Session session = new Session(1L, null, "T", SessionType.VOTE, "ABCDEF", config, 10L, Instant.now());
        session.setStatus(SessionStatus.LIVE);
        return session;
    }

    private SessionVoteBallot ballot(final UUID sessionId, final String payload) {
        return new SessionVoteBallot(sessionId, UUID.randomUUID(), payload, Instant.now());
    }

    // --- submit -------------------------------------------------------------------------------

    @Test
    void submitRejectsWhenSessionIsNotLive() {
        Session session = new Session(1L, null, "T", SessionType.VOTE, "ABCDEF", "{}", 10L, Instant.now());

        assertThatThrownBy(() -> service.submitBallot(session, UUID.randomUUID(), new SubmitBallotRequest(3, null)))
                .isInstanceOf(InvalidSessionStatusException.class);
        verify(ballotRepository, never()).save(any());
    }

    @Test
    void submitFistRejectsAnOutOfRangeRating() {
        Session session = liveVote("{}");
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());
        when(ballotRepository.existsBySessionIdAndParticipantId(session.getId(), pid)).thenReturn(false);

        assertThatThrownBy(() -> service.submitBallot(session, pid, new SubmitBallotRequest(6, null)))
                .isInstanceOf(SessionValidationException.class);
        verify(ballotRepository, never()).save(any());
    }

    @Test
    void submitFistPersistsAndBroadcasts() {
        Session session = liveVote("{}");
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());
        when(ballotRepository.existsBySessionIdAndParticipantId(session.getId(), pid)).thenReturn(false);
        when(ballotRepository.countBySessionId(session.getId())).thenReturn(1L);

        service.submitBallot(session, pid, new SubmitBallotRequest(5, null));

        verify(ballotRepository).save(any());
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void submitRejectsADoubleVote() {
        Session session = liveVote("{}");
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());
        when(ballotRepository.existsBySessionIdAndParticipantId(session.getId(), pid)).thenReturn(true);

        assertThatThrownBy(() -> service.submitBallot(session, pid, new SubmitBallotRequest(3, null)))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("ALREADY_VOTED"));
    }

    @Test
    void submitRejectsWhenTheVoteIsClosed() {
        Session session = liveVote("{}");
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(new SessionVoteState(session.getId(), true)));

        assertThatThrownBy(() -> service.submitBallot(session, UUID.randomUUID(), new SubmitBallotRequest(3, null)))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("VOTE_CLOSED"));
    }

    @Test
    void submitWeightedRejectsAWrongPointsSum() {
        Session session = liveVote(WEIGHTED_CONFIG);
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());
        when(ballotRepository.existsBySessionIdAndParticipantId(session.getId(), pid)).thenReturn(false);

        assertThatThrownBy(() -> service.submitBallot(session, pid, new SubmitBallotRequest(null, Map.of("0", 3, "1", 1))))
                .isInstanceOf(SessionValidationException.class);
        verify(ballotRepository, never()).save(any());
    }

    @Test
    void submitWeightedPersistsWhenPointsSumMatches() {
        Session session = liveVote(WEIGHTED_CONFIG);
        UUID pid = UUID.randomUUID();
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());
        when(ballotRepository.existsBySessionIdAndParticipantId(session.getId(), pid)).thenReturn(false);
        when(ballotRepository.countBySessionId(session.getId())).thenReturn(1L);

        service.submitBallot(session, pid, new SubmitBallotRequest(null, Map.of("0", 3, "1", 2)));

        verify(ballotRepository).save(any());
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- close --------------------------------------------------------------------------------

    @Test
    void closeMarksTheStateClosedAndBroadcasts() {
        Session session = liveVote("{}");
        SessionVoteState state = new SessionVoteState(session.getId(), false);
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.of(state));
        when(ballotRepository.countBySessionId(session.getId())).thenReturn(0L);
        when(ballotRepository.findAllBySessionId(session.getId())).thenReturn(List.of());

        service.close(session);

        assertThat(state.isClosed()).isTrue();
        verify(stateRepository).save(state);
        verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    // --- results ------------------------------------------------------------------------------

    @Test
    void getResultsWithholdsTalliesWhileOpen() {
        Session session = liveVote("{}");
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());
        when(ballotRepository.countBySessionId(session.getId())).thenReturn(2L);

        VoteResultsDto results = service.getResults(session);

        assertThat(results.closed()).isFalse();
        assertThat(results.average()).isNull();
        assertThat(results.options()).isEmpty();
        assertThat(results.ballotCount()).isEqualTo(2L);
    }

    @Test
    void getResultsComputesFistAverageConsensusAndVeto() {
        Session session = liveVote("{}");
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(new SessionVoteState(session.getId(), true)));
        when(ballotRepository.countBySessionId(session.getId())).thenReturn(3L);
        when(ballotRepository.findAllBySessionId(session.getId())).thenReturn(List.of(
                ballot(session.getId(), "{\"value\":5}"),
                ballot(session.getId(), "{\"value\":4}"),
                ballot(session.getId(), "{\"value\":0}")));

        VoteResultsDto results = service.getResults(session);

        assertThat(results.closed()).isTrue();
        assertThat(results.average()).isEqualTo(3.0);
        assertThat(results.consensusLevel()).isEqualTo("MODERATE");
        assertThat(results.veto()).isTrue();
    }

    @Test
    void getResultsSumsWeightedPointsPerOption() {
        Session session = liveVote(WEIGHTED_CONFIG);
        when(stateRepository.findBySessionId(session.getId()))
                .thenReturn(Optional.of(new SessionVoteState(session.getId(), true)));
        when(ballotRepository.countBySessionId(session.getId())).thenReturn(2L);
        when(ballotRepository.findAllBySessionId(session.getId())).thenReturn(List.of(
                ballot(session.getId(), "{\"allocations\":{\"0\":3,\"1\":2}}"),
                ballot(session.getId(), "{\"allocations\":{\"0\":5,\"1\":0}}")));

        VoteResultsDto results = service.getResults(session);

        assertThat(results.options()).extracting(o -> o.optionIndex() + ":" + o.points())
                .containsExactly("0:8", "1:2");
    }
}
