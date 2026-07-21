package fr.pivot.agilite.capacity.connector;

import java.time.LocalDate;

/**
 * A resolved sprint/PI period, as returned by {@link SprintPeriodConnector} (E11 — capacity
 * planning, EN11.1 Wave 2 connectors).
 *
 * @param startDate the period's first calendar day (inclusive)
 * @param endDate   the period's last calendar day (inclusive), never before {@code startDate}
 */
public record SprintPeriod(LocalDate startDate, LocalDate endDate) {
}
