package fr.pivot.collaboratif.session.postitrush;

import fr.pivot.collaboratif.exception.InvalidSessionStatusException;
import fr.pivot.collaboratif.exception.PostitNotFoundException;
import fr.pivot.collaboratif.exception.SessionConflictException;
import fr.pivot.collaboratif.exception.SessionValidationException;
import fr.pivot.collaboratif.session.Participant;
import fr.pivot.collaboratif.session.ParticipantRepository;
import fr.pivot.collaboratif.session.ParticipantRole;
import fr.pivot.collaboratif.session.Session;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;
import fr.pivot.collaboratif.session.postitrush.dto.ClickPostitRequest;
import fr.pivot.collaboratif.session.postitrush.dto.ClickPostitResponse;
import fr.pivot.collaboratif.session.postitrush.dto.PostitRushResultsDto;
import fr.pivot.collaboratif.session.postitrush.dto.PostitRushStateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PostitRushActivityService} (US47.2.1) — server-authoritative round
 * lifecycle, click scoring/combo ladder, error cases, and reconnect/results shapes. Mirrors
 * {@code QuizActivityServiceTest}'s Mockito-only style (US19.3.1) — no Spring context needed.
 */
@ExtendWith(MockitoExtension.class)
class PostitRushActivityServiceTest {

    @Mock
    private SessionPostitRushRoundRepository roundRepository;
    @Mock
    private SessionPostitRushSpawnRepository spawnRepository;
    @Mock
    private SessionPostitRushParticipantRoundRepository participantRoundRepository;
    @Mock
    private ParticipantRepository participantRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private PostitRushActivityService service;

    @BeforeEach
    void setUp() {
        service = new PostitRushActivityService(
                roundRepository, spawnRepository, participantRoundRepository,
                participantRepository, messagingTemplate, new ObjectMapper());
    }

    private Session livePostitRush() {
        Session session = new Session(1L, null, "T", SessionType.POSTIT_RUSH, "ABCDEF", "{}", 10L, Instant.now());
        session.setStatus(SessionStatus.LIVE);
        return session;
    }

    private SessionPostitRushRound activeRound(final UUID sessionId, final Instant startedAt) {
        return new SessionPostitRushRound(sessionId, 1, 90, startedAt);
    }

    // --- security: client can never supply a score -----------------------------------------

    @Test
    void clickPostitRequestOnlyEverCarriesThePostitId() {
        assertThat(ClickPostitRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("postitId");
    }

    // --- startRound -----------------------------------------------------------------------

    @Test
    void startRoundRejectsWhenSessionIsNotLive() {
        Session session = new Session(1L, null, "T", SessionType.POSTIT_RUSH, "ABCDEF", "{}", 10L, Instant.now());

        assertThatThrownBy(() -> service.startRound(session)).isInstanceOf(InvalidSessionStatusException.class);
    }

    @Test
    void startRoundRejectsANonPostitRushSession() {
        Session session = new Session(1L, null, "T", SessionType.QUIZ, "ABCDEF", "{}", 10L, Instant.now());
        session.setStatus(SessionStatus.LIVE);

        assertThatThrownBy(() -> service.startRound(session)).isInstanceOf(InvalidSessionStatusException.class);
    }

    @Test
    void startRoundCreatesTheFirstRoundAndBroadcastsRoundStarted() {
        Session session = livePostitRush();
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.empty());
        when(roundRepository.countBySessionId(session.getId())).thenReturn(0L);

        service.startRound(session);

        ArgumentCaptor<SessionPostitRushRound> captor = ArgumentCaptor.forClass(SessionPostitRushRound.class);
        org.mockito.Mockito.verify(roundRepository).save(captor.capture());
        assertThat(captor.getValue().getRoundNumber()).isEqualTo(1);
        assertThat(captor.getValue().getDurationSeconds()).isEqualTo(PostitRushConstants.DEFAULT_DURATION_SECONDS);
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void startRoundRejectsWhenARoundIsAlreadyActive() {
        Session session = livePostitRush();
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId()))
                .thenReturn(Optional.of(activeRound(session.getId(), Instant.now())));

        assertThatThrownBy(() -> service.startRound(session))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("ROUND_IN_PROGRESS"));
    }

    // --- click: combo ladder / scoring ------------------------------------------------------

    @Test
    void clickRejectsWhenNoRoundIsActive() {
        Session session = livePostitRush();
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.click(session, UUID.randomUUID(), new ClickPostitRequest(UUID.randomUUID())))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("ROUND_NOT_ACTIVE"));
    }

    @Test
    void clickRejectsAnUnknownPostitId() {
        Session session = livePostitRush();
        SessionPostitRushRound round = activeRound(session.getId(), Instant.now());
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.of(round));
        when(spawnRepository.findByIdAndRoundId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.click(session, UUID.randomUUID(), new ClickPostitRequest(UUID.randomUUID())))
                .isInstanceOf(PostitNotFoundException.class);
    }

    @Test
    void clickAwardsBasePointsWithMultiplierOneForTheFirstHit() {
        Session session = livePostitRush();
        UUID participantId = UUID.randomUUID();
        SessionPostitRushRound round = activeRound(session.getId(), Instant.now());
        SessionPostitRushSpawn spawn = new SessionPostitRushSpawn(round.getId(), 10, 20, "amber", Instant.now(), 2000);
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.of(round));
        when(spawnRepository.findByIdAndRoundId(spawn.getId(), round.getId())).thenReturn(Optional.of(spawn));
        when(participantRoundRepository.findByIdRoundIdAndIdParticipantId(round.getId(), participantId))
                .thenReturn(Optional.empty());

        ClickPostitResponse response = service.click(session, participantId, new ClickPostitRequest(spawn.getId()));

        assertThat(response.pointsAwarded()).isEqualTo(10);
        assertThat(response.multiplier()).isEqualTo(1);
        assertThat(response.score()).isEqualTo(10);
        assertThat(response.currentCombo()).isEqualTo(1);
        assertThat(spawn.getClaimedBy()).isEqualTo(participantId);
        // Two broadcasts for this first-ever hit of the round: POSTIT_CLAIMED, then an immediate
        // LEADERBOARD_UPDATED (never throttled — the round has never broadcast a leaderboard yet).
        org.mockito.Mockito.verify(messagingTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void clickAppliesTierTwoMultiplierOnTheThirdConsecutiveHit() {
        Session session = livePostitRush();
        UUID participantId = UUID.randomUUID();
        SessionPostitRushRound round = activeRound(session.getId(), Instant.now());
        SessionPostitRushParticipantRound existing = new SessionPostitRushParticipantRound(round.getId(), participantId);
        existing.registerHit(Instant.now());
        existing.registerHit(Instant.now());
        SessionPostitRushSpawn spawn = new SessionPostitRushSpawn(round.getId(), 5, 5, "sky", Instant.now(), 2000);
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.of(round));
        when(spawnRepository.findByIdAndRoundId(spawn.getId(), round.getId())).thenReturn(Optional.of(spawn));
        when(participantRoundRepository.findByIdRoundIdAndIdParticipantId(round.getId(), participantId))
                .thenReturn(Optional.of(existing));

        ClickPostitResponse response = service.click(session, participantId, new ClickPostitRequest(spawn.getId()));

        assertThat(response.currentCombo()).isEqualTo(3);
        assertThat(response.multiplier()).isEqualTo(2);
        assertThat(response.pointsAwarded()).isEqualTo(20);
        assertThat(response.score()).isEqualTo(40); // 10 + 10 (tier 1 x2) + 20 (tier 2, this hit)
    }

    @Test
    void clickAppliesTierThreeMultiplierOnTheSixthConsecutiveHit() {
        Session session = livePostitRush();
        UUID participantId = UUID.randomUUID();
        SessionPostitRushRound round = activeRound(session.getId(), Instant.now());
        SessionPostitRushParticipantRound existing = new SessionPostitRushParticipantRound(round.getId(), participantId);
        for (int i = 0; i < 5; i++) {
            existing.registerHit(Instant.now());
        }
        SessionPostitRushSpawn spawn = new SessionPostitRushSpawn(round.getId(), 5, 5, "lime", Instant.now(), 2000);
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.of(round));
        when(spawnRepository.findByIdAndRoundId(spawn.getId(), round.getId())).thenReturn(Optional.of(spawn));
        when(participantRoundRepository.findByIdRoundIdAndIdParticipantId(round.getId(), participantId))
                .thenReturn(Optional.of(existing));

        ClickPostitResponse response = service.click(session, participantId, new ClickPostitRequest(spawn.getId()));

        assertThat(response.currentCombo()).isEqualTo(6);
        assertThat(response.multiplier()).isEqualTo(3);
        assertThat(response.pointsAwarded()).isEqualTo(30);
    }

    @Test
    void clickOnAnAlreadyClaimedPostitIsA409AndResetsTheClickersCombo() {
        Session session = livePostitRush();
        UUID participantId = UUID.randomUUID();
        SessionPostitRushRound round = activeRound(session.getId(), Instant.now());
        SessionPostitRushParticipantRound existing = new SessionPostitRushParticipantRound(round.getId(), participantId);
        existing.registerHit(Instant.now());
        existing.registerHit(Instant.now());
        SessionPostitRushSpawn spawn = new SessionPostitRushSpawn(round.getId(), 5, 5, "rose", Instant.now(), 2000);
        spawn.claim(UUID.randomUUID(), Instant.now());
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.of(round));
        when(spawnRepository.findByIdAndRoundId(spawn.getId(), round.getId())).thenReturn(Optional.of(spawn));
        when(participantRoundRepository.findByIdRoundIdAndIdParticipantId(round.getId(), participantId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.click(session, participantId, new ClickPostitRequest(spawn.getId())))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("POSTIT_UNAVAILABLE"));
        assertThat(existing.getCurrentCombo()).isZero();
        assertThat(existing.getScore()).isEqualTo(20); // untouched — a miss never removes banked points
    }

    @Test
    void clickOnAnExpiredPostitIsA409AndResetsTheClickersCombo() {
        Session session = livePostitRush();
        UUID participantId = UUID.randomUUID();
        SessionPostitRushRound round = activeRound(session.getId(), Instant.now());
        SessionPostitRushParticipantRound existing = new SessionPostitRushParticipantRound(round.getId(), participantId);
        existing.registerHit(Instant.now());
        SessionPostitRushSpawn spawn = new SessionPostitRushSpawn(
                round.getId(), 5, 5, "violet", Instant.now().minusMillis(5000), 1200);
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.of(round));
        when(spawnRepository.findByIdAndRoundId(spawn.getId(), round.getId())).thenReturn(Optional.of(spawn));
        when(participantRoundRepository.findByIdRoundIdAndIdParticipantId(round.getId(), participantId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.click(session, participantId, new ClickPostitRequest(spawn.getId())))
                .isInstanceOf(SessionConflictException.class)
                .satisfies(ex -> assertThat(((SessionConflictException) ex).getCode()).isEqualTo("POSTIT_UNAVAILABLE"));
        assertThat(existing.getCurrentCombo()).isZero();
    }

    // --- getState -----------------------------------------------------------------------------

    @Test
    void getStateReportsNoActiveRound() {
        Session session = livePostitRush();
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.empty());

        PostitRushStateDto state = service.getState(session, UUID.randomUUID());

        assertThat(state.roundActive()).isFalse();
        assertThat(state.livePostits()).isEmpty();
    }

    @Test
    void getStateReturnsLivePostitsAndTheCallersOwnScore() {
        Session session = livePostitRush();
        UUID participantId = UUID.randomUUID();
        Instant startedAt = Instant.now().minusSeconds(10);
        SessionPostitRushRound round = activeRound(session.getId(), startedAt);
        SessionPostitRushParticipantRound myState = new SessionPostitRushParticipantRound(round.getId(), participantId);
        myState.registerHit(Instant.now());
        SessionPostitRushSpawn live = new SessionPostitRushSpawn(round.getId(), 1, 2, "teal", Instant.now(), 2000);
        when(roundRepository.findBySessionIdAndEndedAtIsNull(session.getId())).thenReturn(Optional.of(round));
        when(participantRoundRepository.findByIdRoundIdAndIdParticipantId(round.getId(), participantId))
                .thenReturn(Optional.of(myState));
        when(spawnRepository.findAllByRoundIdAndClaimedByIsNullAndExpiredFalse(round.getId()))
                .thenReturn(List.of(live));

        PostitRushStateDto state = service.getState(session, participantId);

        assertThat(state.roundActive()).isTrue();
        assertThat(state.livePostits()).hasSize(1);
        assertThat(state.myScore()).isEqualTo(10);
        assertThat(state.remainingSeconds()).isLessThanOrEqualTo(80);
    }

    // --- getResults -----------------------------------------------------------------------------

    @Test
    void getResultsRejectsWhenNoRoundHasBeenPlayed() {
        Session session = livePostitRush();
        when(roundRepository.findFirstBySessionIdOrderByRoundNumberDesc(session.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getResults(session)).isInstanceOf(SessionValidationException.class);
    }

    @Test
    void getResultsSortsByScoreDescendingWithEarliestReachTiebreak() {
        Session session = livePostitRush();
        SessionPostitRushRound round = activeRound(session.getId(), Instant.now());
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Instant early = Instant.now().minusSeconds(30);
        Instant late = Instant.now();

        SessionPostitRushParticipantRound row1 = new SessionPostitRushParticipantRound(round.getId(), p1);
        row1.registerHit(early);
        row1.registerHit(early);
        SessionPostitRushParticipantRound row2 = new SessionPostitRushParticipantRound(round.getId(), p2);
        row2.registerHit(late);
        row2.registerHit(late);

        when(roundRepository.findFirstBySessionIdOrderByRoundNumberDesc(session.getId())).thenReturn(Optional.of(round));
        when(participantRoundRepository.findAllByIdRoundId(round.getId())).thenReturn(List.of(row2, row1));
        when(participantRepository.findAllById(any())).thenReturn(List.of(
                new Participant(session.getId(), 1L, null, "Alice", Instant.now()),
                new Participant(session.getId(), 2L, null, "Bob", Instant.now())));

        PostitRushResultsDto results = service.getResults(session);

        assertThat(results.standings()).hasSize(2);
        assertThat(results.standings().get(0).rank()).isEqualTo(1);
        assertThat(results.standings().get(0).participantId()).isEqualTo(p1); // earliest-to-reach wins the tie
        assertThat(results.standings().get(0).score()).isEqualTo(results.standings().get(1).score());
        assertThat(results.standings().get(1).rank()).isEqualTo(2);
        assertThat(results.standings().get(1).participantId()).isEqualTo(p2);
    }

    // --- scheduler-driven internals: spawn/expiry (server-generated, never client-decided) ----

    @Test
    void generateSpawnIfDueCreatesASpawnWithinTheServerLifespanRangeAndBroadcasts() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now());
        when(participantRepository.countBySessionIdAndRole(round.getSessionId(), ParticipantRole.PLAYER))
                .thenReturn(3L);

        service.generateSpawnIfDue(round, Instant.now());

        ArgumentCaptor<SessionPostitRushSpawn> captor = ArgumentCaptor.forClass(SessionPostitRushSpawn.class);
        org.mockito.Mockito.verify(spawnRepository).save(captor.capture());
        SessionPostitRushSpawn saved = captor.getValue();
        assertThat(saved.getLifespanMs()).isBetween(PostitRushConstants.LIFESPAN_MIN_MS, PostitRushConstants.LIFESPAN_MAX_MS);
        assertThat(saved.getX()).isBetween(0.0, 100.0);
        assertThat(saved.getY()).isBetween(0.0, 100.0);
        assertThat(saved.getColorKey()).isIn((Object[]) PostitRushConstants.COLOR_KEYS);
        assertThat(round.getNextSpawnAt()).isAfter(Instant.now());
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void generateSpawnIfDueDoesNothingBeforeItsScheduledInstant() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now());
        round.setNextSpawnAt(Instant.now().plusSeconds(30));

        service.generateSpawnIfDue(round, Instant.now());

        org.mockito.Mockito.verify(spawnRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void expireDueSpawnsMarksAnElapsedUnclaimedSpawnExpiredAndBroadcasts() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now());
        SessionPostitRushSpawn stale = new SessionPostitRushSpawn(
                round.getId(), 1, 1, "amber", Instant.now().minusMillis(3000), 1200);
        when(spawnRepository.findAllByRoundIdAndClaimedByIsNullAndExpiredFalse(round.getId())).thenReturn(List.of(stale));

        service.expireDueSpawns(round, Instant.now());

        assertThat(stale.isExpired()).isTrue();
        org.mockito.Mockito.verify(spawnRepository).save(stale);
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void expireDueSpawnsLeavesAStillLiveSpawnAlone() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now());
        SessionPostitRushSpawn fresh = new SessionPostitRushSpawn(round.getId(), 1, 1, "amber", Instant.now(), 2500);
        when(spawnRepository.findAllByRoundIdAndClaimedByIsNullAndExpiredFalse(round.getId())).thenReturn(List.of(fresh));

        service.expireDueSpawns(round, Instant.now());

        assertThat(fresh.isExpired()).isFalse();
        org.mockito.Mockito.verify(spawnRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void endRoundIfElapsedEndsAndBroadcastsOnceDurationHasPassed() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now().minusSeconds(200));

        boolean ended = service.endRoundIfElapsed(round, Instant.now());

        assertThat(ended).isTrue();
        assertThat(round.getEndedAt()).isNotNull();
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void endRoundIfElapsedDoesNothingWhileTimeRemains() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now());

        boolean ended = service.endRoundIfElapsed(round, Instant.now());

        assertThat(ended).isFalse();
        assertThat(round.getEndedAt()).isNull();
    }

    // --- leaderboard throttle -----------------------------------------------------------------

    @Test
    void flushLeaderboardIfDueBroadcastsImmediatelyWhenNeverBroadcastBefore() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now());
        round.setLeaderboardDirty(true);
        when(participantRoundRepository.findAllByIdRoundId(round.getId())).thenReturn(List.of());
        when(participantRepository.countBySessionIdAndRole(any(), any())).thenReturn(1L);

        service.flushLeaderboardIfDue(round.getSessionId(), round, Instant.now());

        assertThat(round.isLeaderboardDirty()).isFalse();
        assertThat(round.getLeaderboardBroadcastAt()).isNotNull();
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void flushLeaderboardIfDueSkipsWithinTheThrottleWindow() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now());
        round.setLeaderboardDirty(true);
        round.setLeaderboardBroadcastAt(Instant.now());

        service.flushLeaderboardIfDue(round.getSessionId(), round, Instant.now().plusMillis(100));

        assertThat(round.isLeaderboardDirty()).isTrue(); // still pending — throttle window not elapsed
        org.mockito.Mockito.verify(messagingTemplate, org.mockito.Mockito.never())
                .convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void flushLeaderboardIfDueDoesNothingWhenNothingChanged() {
        SessionPostitRushRound round = activeRound(UUID.randomUUID(), Instant.now());

        service.flushLeaderboardIfDue(round.getSessionId(), round, Instant.now());

        org.mockito.Mockito.verify(messagingTemplate, org.mockito.Mockito.never())
                .convertAndSend(anyString(), any(Object.class));
    }
}
