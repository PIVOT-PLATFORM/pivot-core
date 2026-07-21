package fr.pivot.agilite.capacity.connector;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Unit tests for {@link DefaultHolidayConnector} (EN11.1 Wave 2 connectors, TODO(EN22.3)). */
class DefaultHolidayConnectorTest {

    private final DefaultHolidayConnector connector = new DefaultHolidayConnector();

    @Test
    void holidaysFor_knownLocality_returnsAnEmptyImmutableSet() {
        Set<LocalDate> holidays = connector.holidaysFor(
                "FR", LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17));

        assertThat(holidays).isEmpty();
        assertThatCode(() -> holidays.add(LocalDate.of(2026, 7, 14)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void holidaysFor_nullLocality_returnsEmptySet() {
        Set<LocalDate> holidays = connector.holidaysFor(
                null, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17));

        assertThat(holidays).isEmpty();
    }
}
