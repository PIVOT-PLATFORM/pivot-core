package fr.pivot.agilite.capacity.connector;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Resolves the calendar period (start/end) of a sprint or PI (E11 — capacity planning, EN11.1
 * Wave 2 connectors).
 *
 * <p>The default implementation ({@link EventDatesSprintPeriodConnector}) simply reads the
 * persisted {@code CapacityEvent}'s own dates — no I/O. A future connector could instead resolve
 * the period from an external planning tool (e.g. a Jira sprint or an ADO iteration), keeping this
 * interface as the seam so {@code CapacitySummaryService} never depends on a concrete source.
 */
public interface SprintPeriodConnector {

    /**
     * Resolves the sprint/PI period for the given event.
     *
     * @param eventId   the capacity event's identifier
     * @param startDate the event's own first calendar day (inclusive), as a fallback/hint
     * @param endDate   the event's own last calendar day (inclusive), as a fallback/hint
     * @return the resolved period, never {@code null}
     */
    SprintPeriod resolvePeriod(UUID eventId, LocalDate startDate, LocalDate endDate);
}
