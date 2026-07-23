package fr.pivot.collaboratif.session.poll;

import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.poll.dto.PollOptionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PollActivityServiceTest {

    @Mock
    private SessionPollOptionRepository optionRepository;
    @Mock
    private SessionPollVoteRepository voteRepository;
    @Mock
    private SessionPollStateRepository stateRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private PollActivityService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new PollActivityService(optionRepository, voteRepository, stateRepository, messagingTemplate, objectMapper);
    }

    private Session liveSession(final String config) {
        Session session = new Session(1L, null, "T", SessionType.POLL, "ABCDEF", config, 10L, Instant.now());
        session.setStatus(SessionStatus.LIVE);
        return session;
    }

    @Test
    void initializeFromConfigCreatesOneOptionPerEntry() {
        service.initializeFromConfig(UUID.randomUUID(), objectMapper.readTree("{\"options\":[\"A\",\"B\",\"C\"]}"));

        org.mockito.Mockito.verify(optionRepository, org.mockito.Mockito.times(3)).save(any());
    }

    @Test
    void initializeFromConfigRejectsFewerThanTwoOptions() {
        assertThatThrownBy(() -> service.initializeFromConfig(
                UUID.randomUUID(), objectMapper.readTree("{\"options\":[\"A\"]}")))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void initializeFromConfigRejectsMoreThanEightOptions() {
        assertThatThrownBy(() -> service.initializeFromConfig(UUID.randomUUID(), objectMapper.readTree(
                "{\"options\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\"]}")))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void voteRejectsWhenSessionIsNotLive() {
        Session session = new Session(1L, null, "T", SessionType.POLL, "ABCDEF", "{}", 10L, Instant.now());

        assertThatThrownBy(() -> service.vote(session, UUID.randomUUID(), List.of(UUID.randomUUID())))
                .isInstanceOf(InvalidSessionStatusException.class);
    }

    @Test
    void voteRejectsAnUnknownOptionId() {
        Session session = liveSession("{}");
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(session.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.vote(session, UUID.randomUUID(), List.of(UUID.randomUUID())))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void voteRejectsMultipleOptionsWhenAllowMultipleIsFalse() {
        UUID option1 = UUID.randomUUID();
        UUID option2 = UUID.randomUUID();
        Session session = liveSession("{\"allowMultiple\":false}");
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(session.getId())).thenReturn(List.of(
                new SessionPollOption(session.getId(), "A", 0), new SessionPollOption(session.getId(), "B", 1)));

        assertThatThrownBy(() -> service.vote(session, UUID.randomUUID(), List.of(option1, option2)))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void voteUpsertsAnExistingVoteAndBroadcastsResults() {
        SessionPollOption option = new SessionPollOption(UUID.randomUUID(), "A", 0);
        ReflectionTestUtils.setField(option, "id", UUID.randomUUID());
        Session session = liveSession("{\"allowMultiple\":false}");
        UUID participantId = UUID.randomUUID();
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(session.getId())).thenReturn(List.of(option));
        SessionPollVote existingVote = new SessionPollVote(session.getId(), participantId, "[]", Instant.now());
        when(voteRepository.findBySessionIdAndParticipantId(session.getId(), participantId))
                .thenReturn(Optional.of(existingVote));
        when(voteRepository.findAllBySessionId(session.getId())).thenReturn(List.of(existingVote));
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());

        service.vote(session, participantId, List.of(option.getId()));

        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.anyString(), any(Object.class));
    }

    @Test
    void getResultsOmitsCountsWhenHidden() {
        UUID sessionId = UUID.randomUUID();
        SessionPollOption option = new SessionPollOption(sessionId, "A", 0);
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(sessionId)).thenReturn(List.of(option));
        when(stateRepository.findBySessionId(sessionId)).thenReturn(Optional.of(new SessionPollState(sessionId, true)));
        when(voteRepository.findAllBySessionId(sessionId)).thenReturn(List.of());

        List<PollOptionResult> results = service.getResults(sessionId, false);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).count()).isNull();
        assertThat(results.get(0).percent()).isNull();
    }

    @Test
    void getResultsAlwaysIncludesCountsForTheFacilitatorEvenWhenHidden() {
        UUID sessionId = UUID.randomUUID();
        SessionPollOption option = new SessionPollOption(sessionId, "A", 0);
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(sessionId)).thenReturn(List.of(option));
        when(voteRepository.findAllBySessionId(sessionId)).thenReturn(List.of());

        List<PollOptionResult> results = service.getResults(sessionId, true);

        assertThat(results.get(0).count()).isEqualTo(0);
        assertThat(results.get(0).percent()).isEqualTo(0.0);
    }

    @Test
    void voteRejectsAnEmptySelection() {
        Session session = liveSession("{}");
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(session.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.vote(session, UUID.randomUUID(), List.of()))
                .isInstanceOf(SessionValidationException.class);
    }

    @Test
    void voteAcceptsMultipleOptionsWhenAllowMultipleIsTrue() {
        SessionPollOption optionA = new SessionPollOption(UUID.randomUUID(), "A", 0);
        ReflectionTestUtils.setField(optionA, "id", UUID.randomUUID());
        SessionPollOption optionB = new SessionPollOption(UUID.randomUUID(), "B", 1);
        ReflectionTestUtils.setField(optionB, "id", UUID.randomUUID());
        Session session = liveSession("{\"allowMultiple\":true}");
        UUID participantId = UUID.randomUUID();
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(session.getId()))
                .thenReturn(List.of(optionA, optionB));
        when(voteRepository.findBySessionIdAndParticipantId(session.getId(), participantId))
                .thenReturn(Optional.empty());
        when(voteRepository.findAllBySessionId(session.getId())).thenReturn(List.of());
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());

        service.vote(session, participantId, List.of(optionA.getId(), optionB.getId()));

        org.mockito.Mockito.verify(voteRepository).save(any());
    }

    /**
     * When {@link PollActivityService#readAllowMultiple} is fed a session with a malformed
     * (non-JSON) {@code config} blob, it must not propagate the parse failure — the caught
     * exception's fallback ({@code allowMultiple = false}) is what makes a single-option vote
     * still work rather than a poll being permanently unvotable due to a corrupted config.
     */
    @Test
    void voteToleratesAnUnparsableConfigAndFallsBackToSingleSelection() {
        SessionPollOption option = new SessionPollOption(UUID.randomUUID(), "A", 0);
        ReflectionTestUtils.setField(option, "id", UUID.randomUUID());
        Session session = liveSession("not-json");
        UUID participantId = UUID.randomUUID();
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(session.getId())).thenReturn(List.of(option));
        when(voteRepository.findBySessionIdAndParticipantId(session.getId(), participantId))
                .thenReturn(Optional.empty());
        when(voteRepository.findAllBySessionId(session.getId())).thenReturn(List.of());
        when(stateRepository.findBySessionId(session.getId())).thenReturn(Optional.empty());

        service.vote(session, participantId, List.of(option.getId()));

        org.mockito.Mockito.verify(voteRepository).save(any());
    }

    @Test
    void hideResultsCreatesStateWhenNoneExistsAndBroadcasts() {
        UUID sessionId = UUID.randomUUID();
        when(stateRepository.findBySessionId(sessionId)).thenReturn(Optional.empty());
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(sessionId)).thenReturn(List.of());
        when(voteRepository.findAllBySessionId(sessionId)).thenReturn(List.of());

        service.hideResults(sessionId);

        org.mockito.Mockito.verify(stateRepository).save(
                org.mockito.ArgumentMatchers.argThat(SessionPollState::isResultsHidden));
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.anyString(), any(Object.class));
    }

    @Test
    void showResultsFlipsAnExistingHiddenStateBackToVisibleAndBroadcasts() {
        UUID sessionId = UUID.randomUUID();
        SessionPollState state = new SessionPollState(sessionId, true);
        when(stateRepository.findBySessionId(sessionId)).thenReturn(Optional.of(state));
        when(optionRepository.findAllBySessionIdOrderBySortOrderAsc(sessionId)).thenReturn(List.of());
        when(voteRepository.findAllBySessionId(sessionId)).thenReturn(List.of());

        service.showResults(sessionId);

        assertThat(state.isResultsHidden()).isFalse();
        org.mockito.Mockito.verify(stateRepository).save(state);
    }
}
