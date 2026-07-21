package fr.pivot.agilite.capacity.connector;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link EventDatesSprintPeriodConnector} (EN11.1 Wave 2 connectors). */
class EventDatesSprintPeriodConnectorTest {

    private final EventDatesSprintPeriodConnector connector = new EventDatesSprintPeriodConnector();

    @Test
    void resolvePeriod_returnsTheGivenEventDatesUnchanged() {
        LocalDate startDate = LocalDate.of(2026, 7, 6);
        LocalDate endDate = LocalDate.of(2026, 7, 17);

        SprintPeriod period = connector.resolvePeriod(UUID.randomUUID(), startDate, endDate);

        assertThat(period.startDate()).isEqualTo(startDate);
        assertThat(period.endDate()).isEqualTo(endDate);
    }
}
