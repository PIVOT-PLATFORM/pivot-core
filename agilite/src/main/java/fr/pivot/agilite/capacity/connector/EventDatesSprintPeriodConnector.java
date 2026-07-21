package fr.pivot.agilite.capacity.connector;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Default {@link SprintPeriodConnector}: the period is simply the persisted capacity event's own
 * {@code startDate}/{@code endDate} — no external lookup, no I/O (E11 — capacity planning, EN11.1
 * Wave 2 connectors).
 *
 * <p>Extension point: a future connector could instead call out to an external planning tool
 * (e.g. resolve the sprint's actual boundaries from a Jira/ADO integration bus event) and fall
 * back to these event dates when the external source has nothing for this event.
 */
@Component
public class EventDatesSprintPeriodConnector implements SprintPeriodConnector {

    @Override
    public SprintPeriod resolvePeriod(final UUID eventId, final LocalDate startDate, final LocalDate endDate) {
        return new SprintPeriod(startDate, endDate);
    }
}
