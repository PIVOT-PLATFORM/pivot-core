package fr.pivot.agilite.capacity.connector;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * Fallback {@link HolidayConnector}: always returns an empty set, no I/O, no cache (E11 —
 * capacity planning, EN11.1 Wave 2 connectors, TODO(EN22.3)).
 *
 * <p>Extension point: a real implementation resolves holidays via the EN22.3 calendar bus (per
 * ADR-006/ADR-008, no FK cross-module — a local, module-owned cache of the bus-published dates is
 * admissible) and replaces this bean. Until then, {@code CapacitySummaryService} computes capacity
 * with no public holidays excluded, same behavior as before this connector existed.
 */
@Component
public class DefaultHolidayConnector implements HolidayConnector {

    @Override
    public Set<LocalDate> holidaysFor(final String locality, final LocalDate startDate, final LocalDate endDate) {
        return Set.of();
    }
}
