package fr.pivot.agilite.capacity.connector;

import java.time.LocalDate;
import java.util.Set;

/**
 * Resolves the public holidays applicable to a locality over a period, feeding {@code
 * CapacityEventInput#holidays} (E11 — capacity planning, EN11.1 Wave 2 connectors, TODO(EN22.3)).
 *
 * <p>Per ADR-006/ADR-008, {@code agilite} never holds a foreign key across module boundaries — a
 * locality's holiday calendar is expected to live in (or be published by) the EN22.3 calendar
 * module. This interface is the seam: a future implementation subscribes to the EN22.3 bus (or
 * queries it) and may keep a local, module-owned cache of the resolved dates — never a cross-module
 * FK. {@link DefaultHolidayConnector} is the only bound implementation until then, always
 * returning an empty set.
 */
public interface HolidayConnector {

    /**
     * Resolves the holidays for one locality over a period.
     *
     * @param locality  the locality to resolve holidays for (e.g. an ISO country/region code), or
     *                  {@code null}/blank when the caller has no locality to resolve against
     * @param startDate the period's first calendar day (inclusive)
     * @param endDate   the period's last calendar day (inclusive)
     * @return the resolved holiday dates, an immutable set, never {@code null} — empty when the
     *         connector has nothing for this locality/period
     */
    Set<LocalDate> holidaysFor(String locality, LocalDate startDate, LocalDate endDate);
}
