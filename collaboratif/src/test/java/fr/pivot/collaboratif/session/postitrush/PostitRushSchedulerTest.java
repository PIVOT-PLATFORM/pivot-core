package fr.pivot.collaboratif.session.postitrush;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PostitRushScheduler} (US47.2.1) — verifies the poll loop drives round
 * end/spawn/expiry/leaderboard-flush for every active round, and short-circuits the rest of a
 * round's advance once it has just ended (no spawn/leaderboard work on an ended round).
 */
@ExtendWith(MockitoExtension.class)
class PostitRushSchedulerTest {

    @Mock
    private SessionPostitRushRoundRepository roundRepository;
    @Mock
    private PostitRushActivityService activityService;

    private PostitRushScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PostitRushScheduler(roundRepository, activityService);
    }

    @Test
    void tickDoesNothingWhenNoRoundIsActive() {
        when(roundRepository.findAllByEndedAtIsNull()).thenReturn(List.of());

        scheduler.tick();

        verify(activityService, never()).endRoundIfElapsed(any(), any());
    }

    @Test
    void tickAdvancesAnActiveRoundThatHasNotYetElapsed() {
        SessionPostitRushRound round = new SessionPostitRushRound(UUID.randomUUID(), 1, 90, Instant.now());
        when(roundRepository.findAllByEndedAtIsNull()).thenReturn(List.of(round));
        when(activityService.endRoundIfElapsed(eq(round), any())).thenReturn(false);

        scheduler.tick();

        verify(activityService).endRoundIfElapsed(eq(round), any());
        verify(activityService).expireDueSpawns(eq(round), any());
        verify(activityService).generateSpawnIfDue(eq(round), any());
        verify(activityService).flushLeaderboardIfDue(eq(round.getSessionId()), eq(round), any());
    }

    @Test
    void tickStopsAdvancingARoundThatJustEnded() {
        SessionPostitRushRound round = new SessionPostitRushRound(
                UUID.randomUUID(), 1, 90, Instant.now().minusSeconds(200));
        when(roundRepository.findAllByEndedAtIsNull()).thenReturn(List.of(round));
        when(activityService.endRoundIfElapsed(eq(round), any())).thenReturn(true);

        scheduler.tick();

        verify(activityService).endRoundIfElapsed(eq(round), any());
        verify(activityService, never()).expireDueSpawns(any(), any());
        verify(activityService, never()).generateSpawnIfDue(any(), any());
        verify(activityService, never()).flushLeaderboardIfDue(any(), any(), any());
    }

    @Test
    void tickSurvivesOneRoundFailingAndStillAdvancesTheRest() {
        SessionPostitRushRound failing = new SessionPostitRushRound(UUID.randomUUID(), 1, 90, Instant.now());
        SessionPostitRushRound healthy = new SessionPostitRushRound(UUID.randomUUID(), 1, 90, Instant.now());
        when(roundRepository.findAllByEndedAtIsNull()).thenReturn(List.of(failing, healthy));
        when(activityService.endRoundIfElapsed(eq(failing), any())).thenThrow(new RuntimeException("boom"));
        when(activityService.endRoundIfElapsed(eq(healthy), any())).thenReturn(false);

        scheduler.tick();

        verify(activityService, times(1)).expireDueSpawns(eq(healthy), any());
    }
}
