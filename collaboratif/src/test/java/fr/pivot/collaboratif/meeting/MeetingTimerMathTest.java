package fr.pivot.collaboratif.meeting;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MeetingTimerMath} (US12.2.1 AC-02/AC-04/AC-S4) — the deterministic core
 * every timer-carrying broadcast and {@code GET .../live} response ultimately traces back to.
 * Every case here pins a fixed {@link Instant} pair (never {@code Instant.now()}), so the
 * expected {@code elapsedSeconds}/{@code remainingSeconds}/{@code overtimeSeconds} are exact, not
 * approximate.
 */
class MeetingTimerMathTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-01T10:00:00Z");

    private AgendaItem itemStartedAt(final Instant startedAt, final int durationMinutes) {
        Meeting meeting = new Meeting(1L, null, "M", Instant.now(), 30, 1L, Instant.now());
        AgendaItem item = meeting.addAgendaItem("Point", durationMinutes, AgendaItemType.INFO, null);
        item.markCurrent(startedAt);
        return item;
    }

    @Test
    void withinAllottedTime_computesPositiveRemainingAndNoOvertime() {
        AgendaItem item = itemStartedAt(STARTED_AT, 5);
        Instant now = STARTED_AT.plusSeconds(120);

        MeetingTimerMath.Snapshot snapshot = MeetingTimerMath.compute(item, now);

        assertThat(snapshot.elapsedSeconds()).isEqualTo(120);
        assertThat(snapshot.remainingSeconds()).isEqualTo(180);
        assertThat(snapshot.overtime()).isFalse();
        assertThat(snapshot.overtimeSeconds()).isZero();
    }

    @Test
    void exactlyAtTheAllottedBoundary_isNotYetOvertime() {
        AgendaItem item = itemStartedAt(STARTED_AT, 5);
        Instant now = STARTED_AT.plusSeconds(300);

        MeetingTimerMath.Snapshot snapshot = MeetingTimerMath.compute(item, now);

        assertThat(snapshot.remainingSeconds()).isZero();
        assertThat(snapshot.overtime()).isFalse();
        assertThat(snapshot.overtimeSeconds()).isZero();
    }

    @Test
    void pastTheAllottedTime_computesNegativeRemainingAndPositiveOvertime() {
        AgendaItem item = itemStartedAt(STARTED_AT, 5);
        Instant now = STARTED_AT.plusSeconds(310);

        MeetingTimerMath.Snapshot snapshot = MeetingTimerMath.compute(item, now);

        assertThat(snapshot.elapsedSeconds()).isEqualTo(310);
        assertThat(snapshot.remainingSeconds()).isEqualTo(-10);
        assertThat(snapshot.overtime()).isTrue();
        assertThat(snapshot.overtimeSeconds()).isEqualTo(10);
    }

    @Test
    void withNoCurrentItemStartedAt_returnsAZeroSnapshot() {
        Meeting meeting = new Meeting(1L, null, "M", Instant.now(), 30, 1L, Instant.now());
        AgendaItem item = meeting.addAgendaItem("Point", 5, AgendaItemType.INFO, null);

        MeetingTimerMath.Snapshot snapshot = MeetingTimerMath.compute(item, Instant.now());

        assertThat(snapshot.elapsedSeconds()).isZero();
        assertThat(snapshot.overtime()).isFalse();
    }

    @Test
    void withNullItem_returnsAZeroSnapshot() {
        MeetingTimerMath.Snapshot snapshot = MeetingTimerMath.compute(null, Instant.now());

        assertThat(snapshot.elapsedSeconds()).isZero();
        assertThat(snapshot.remainingSeconds()).isZero();
        assertThat(snapshot.overtime()).isFalse();
        assertThat(snapshot.overtimeSeconds()).isZero();
    }
}
