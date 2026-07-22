package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for {@link CapacityVelocityForecastCalculator} — pure, no Spring/database context
 * (US11.6.3).
 */
class CapacityVelocityForecastCalculatorTest {

    @Test
    void forecast_noHistory_returnsNoHistoryBasis() {
        CapacityVelocityForecastCalculator.Forecast forecast =
                CapacityVelocityForecastCalculator.forecast(List.of(), 3);

        assertThat(forecast.basis()).isEqualTo("NO_HISTORY");
        assertThat(forecast.avgVelocity()).isNull();
        assertThat(forecast.confidenceInterval()).isNull();
    }

    @Test
    void forecast_regularVelocity_narrowConfidence() {
        // 3 sprints, each 10 net person-days, 20 completed points => velocity 2.0 every time.
        List<CapacityVelocityForecastCalculator.SprintHistoryEntry> history = List.of(
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 20),
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 20),
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 20));

        CapacityVelocityForecastCalculator.Forecast forecast =
                CapacityVelocityForecastCalculator.forecast(history, 3);

        assertThat(forecast.basis()).isEqualTo("HISTORY");
        assertThat(forecast.avgVelocity()).isEqualTo(2.0, offset(0.001));
        assertThat(forecast.confidenceInterval()).isEqualTo("NARROW");
    }

    @Test
    void forecast_highlyVariableVelocity_wideConfidence() {
        // Coefficient of variation well above 25%.
        List<CapacityVelocityForecastCalculator.SprintHistoryEntry> history = List.of(
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 5),
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 40),
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 8));

        CapacityVelocityForecastCalculator.Forecast forecast =
                CapacityVelocityForecastCalculator.forecast(history, 3);

        assertThat(forecast.confidenceInterval()).isEqualTo("WIDE");
    }

    @Test
    void forecast_windowLimitsHistory_onlyMostRecentEntriesUsed() {
        List<CapacityVelocityForecastCalculator.SprintHistoryEntry> history = List.of(
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 20), // most recent
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 100)); // outside window

        CapacityVelocityForecastCalculator.Forecast forecast =
                CapacityVelocityForecastCalculator.forecast(history, 1);

        assertThat(forecast.avgVelocity()).isEqualTo(2.0, offset(0.001));
    }

    @Test
    void forecast_zeroNetPersonDaysEntry_excludedFromWeighting() {
        List<CapacityVelocityForecastCalculator.SprintHistoryEntry> history = List.of(
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(0.0, 0),
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(10.0, 20));

        CapacityVelocityForecastCalculator.Forecast forecast =
                CapacityVelocityForecastCalculator.forecast(history, 2);

        assertThat(forecast.avgVelocity()).isEqualTo(2.0, offset(0.001));
    }

    @Test
    void forecast_weightedBySprintSize_largerSprintDominatesAverage() {
        List<CapacityVelocityForecastCalculator.SprintHistoryEntry> history = List.of(
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(1.0, 10), // velocity 10, weight 1
                new CapacityVelocityForecastCalculator.SprintHistoryEntry(9.0, 18)); // velocity 2, weight 9

        CapacityVelocityForecastCalculator.Forecast forecast =
                CapacityVelocityForecastCalculator.forecast(history, 2);

        // (10*1 + 2*9) / 10 = 2.8
        assertThat(forecast.avgVelocity()).isEqualTo(2.8, offset(0.001));
    }
}
