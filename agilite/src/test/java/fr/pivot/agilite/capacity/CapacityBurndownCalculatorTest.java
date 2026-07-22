package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for {@link CapacityBurndownCalculator} — pure, no Spring/database context, {@link
 * Clock} always injected (US11.4.2).
 */
class CapacityBurndownCalculatorTest {

    // Monday 2026-01-05 .. Friday 2026-01-09: 5 working days.
    private static final LocalDate START = LocalDate.of(2026, 1, 5);
    private static final LocalDate END = LocalDate.of(2026, 1, 9);

    @Test
    void idealCurve_nullCommittedPoints_returnsEmpty() {
        assertThat(CapacityBurndownCalculator.idealCurve(START, END, null)).isEmpty();
    }

    @Test
    void idealCurve_linearDecrementToZeroOnLastWorkingDay() {
        List<CapacityBurndownCalculator.IdealPoint> curve = CapacityBurndownCalculator.idealCurve(START, END, 20);

        assertThat(curve).hasSize(5);
        assertThat(curve.get(0).date()).isEqualTo(START);
        assertThat(curve.get(0).pointsRemaining()).isEqualTo(20.0, offset(0.001));
        assertThat(curve.get(4).date()).isEqualTo(END);
        assertThat(curve.get(4).pointsRemaining()).isEqualTo(0.0, offset(0.001));
        // Midpoint (day index 2 of 5) is exactly half.
        assertThat(curve.get(2).pointsRemaining()).isEqualTo(10.0, offset(0.001));
    }

    @Test
    void idealCurve_singleWorkingDay_isZero() {
        LocalDate saturday = LocalDate.of(2026, 1, 10);
        LocalDate mondayAfter = LocalDate.of(2026, 1, 12);
        List<CapacityBurndownCalculator.IdealPoint> curve =
                CapacityBurndownCalculator.idealCurve(saturday, mondayAfter, 8);

        assertThat(curve).hasSize(1);
        assertThat(curve.get(0).pointsRemaining()).isZero();
    }

    @Test
    void isAtRisk_actualAboveIdealForTwoConsecutiveDays_returnsTrue() {
        List<CapacityBurndownCalculator.IdealPoint> ideal = CapacityBurndownCalculator.idealCurve(START, END, 20);
        List<CapacityBurndownCalculator.ActualPoint> actual = List.of(
                new CapacityBurndownCalculator.ActualPoint(START, 20),
                new CapacityBurndownCalculator.ActualPoint(START.plusDays(1), 18), // ideal ~15, above
                new CapacityBurndownCalculator.ActualPoint(START.plusDays(2), 15)); // ideal 10, above -> 2 consecutive

        assertThat(CapacityBurndownCalculator.isAtRisk(ideal, actual)).isTrue();
    }

    @Test
    void isAtRisk_singleDayAboveIdeal_returnsFalse() {
        List<CapacityBurndownCalculator.IdealPoint> ideal = CapacityBurndownCalculator.idealCurve(START, END, 20);
        List<CapacityBurndownCalculator.ActualPoint> actual =
                List.of(new CapacityBurndownCalculator.ActualPoint(START.plusDays(1), 19)); // ideal 15, above but alone

        assertThat(CapacityBurndownCalculator.isAtRisk(ideal, actual)).isFalse();
    }

    @Test
    void isAtRisk_actualBelowIdeal_returnsFalse() {
        List<CapacityBurndownCalculator.IdealPoint> ideal = CapacityBurndownCalculator.idealCurve(START, END, 20);
        List<CapacityBurndownCalculator.ActualPoint> actual = List.of(
                new CapacityBurndownCalculator.ActualPoint(START, 15),
                new CapacityBurndownCalculator.ActualPoint(START.plusDays(1), 10));

        assertThat(CapacityBurndownCalculator.isAtRisk(ideal, actual)).isFalse();
    }

    @Test
    void isStale_eventNotYetStarted_returnsFalse() {
        Clock clock = fixedClockAt(START.minusDays(1));

        assertThat(CapacityBurndownCalculator.isStale(List.of(), START, END, clock)).isFalse();
    }

    @Test
    void isStale_eventOngoingNoEntries_returnsTrue() {
        Clock clock = fixedClockAt(START.plusDays(1));

        assertThat(CapacityBurndownCalculator.isStale(List.of(), START, END, clock)).isTrue();
    }

    @Test
    void isStale_recentEntry_returnsFalse() {
        List<CapacityBurndownCalculator.ActualPoint> actual =
                List.of(new CapacityBurndownCalculator.ActualPoint(START.plusDays(1), 10));
        Clock clock = fixedClockAt(START.plusDays(2));

        assertThat(CapacityBurndownCalculator.isStale(actual, START, END, clock)).isFalse();
    }

    @Test
    void isStale_entryThreeDaysOld_returnsTrue() {
        List<CapacityBurndownCalculator.ActualPoint> actual =
                List.of(new CapacityBurndownCalculator.ActualPoint(START, 15));
        Clock clock = fixedClockAt(START.plusDays(3));

        assertThat(CapacityBurndownCalculator.isStale(actual, START, END, clock)).isTrue();
    }

    private static Clock fixedClockAt(final LocalDate date) {
        return Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }
}
