package fr.pivot.collaboratif.meeting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MeetingTimerScheduler} (US12.2.1 AC-02/AC-04/AC-05) — verifies the
 * scalar-id-projection + per-meeting-tick delegation shape, and that a single meeting's tick
 * failing never aborts the remaining ticks in the same run (a bad/expired meeting must not
 * silently stop the 1 Hz broadcast for every other {@code IN_PROGRESS} meeting).
 */
@ExtendWith(MockitoExtension.class)
class MeetingTimerSchedulerTest {

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private MeetingAnimationService animationService;

    private MeetingTimerScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MeetingTimerScheduler(meetingRepository, animationService);
    }

    @Test
    void tick_ticksEveryInProgressMeetingIdReturnedByTheRepository() {
        UUID meetingA = UUID.randomUUID();
        UUID meetingB = UUID.randomUUID();
        when(meetingRepository.findIdsByStatus(MeetingStatus.IN_PROGRESS)).thenReturn(List.of(meetingA, meetingB));

        scheduler.tick();

        InOrder order = inOrder(animationService);
        order.verify(animationService).tick(meetingA);
        order.verify(animationService).tick(meetingB);
    }

    @Test
    void tick_withNoInProgressMeetings_neverCallsAnimationService() {
        when(meetingRepository.findIdsByStatus(MeetingStatus.IN_PROGRESS)).thenReturn(List.of());

        scheduler.tick();

        verify(animationService, org.mockito.Mockito.never()).tick(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void tick_whenOneMeetingsTickThrows_stillTicksTheRemainingMeetings() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(meetingRepository.findIdsByStatus(MeetingStatus.IN_PROGRESS)).thenReturn(List.of(failing, healthy));
        doThrow(new RuntimeException("boom")).when(animationService).tick(eq(failing));

        scheduler.tick();

        verify(animationService).tick(healthy);
    }
}
