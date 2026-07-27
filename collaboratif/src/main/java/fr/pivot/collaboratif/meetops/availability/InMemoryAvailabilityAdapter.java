package fr.pivot.collaboratif.meetops.availability;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stub implementation of {@link AvailabilityPort} (US12.4.1) — the real connector
 * (calendar/absence sources, EN22.3 "Disponibilités agrégées") is explicitly out of this sprint's
 * scope (see the pivot-docs AC table's own framing note); this adapter lets {@code
 * BestSlotEngine} be built and tested end-to-end today against a swappable free/busy source.
 *
 * <p>Every participant is available by default (US12.4.1 AC — "sans agenda connecté [...]
 * disponible par défaut"); {@link #registerBusyPeriod} lets a caller (test, or a future admin
 * tool) declare a busy interval for a given participant, simulating a connected calendar. Busy
 * periods are matched by simple interval overlap.
 *
 * <p><strong>Thread-safety.</strong> Backed by a {@link ConcurrentHashMap} — safe for concurrent
 * reads, and for tests running against the same Spring context to each register their own
 * participant refs without needing external synchronization (as long as distinct tests use
 * distinct {@code participantRef} values, which they should regardless to keep assertions
 * independent).
 */
@Component
public class InMemoryAvailabilityAdapter implements AvailabilityPort {

    private final Map<String, List<BusyPeriod>> busyPeriodsByParticipant = new ConcurrentHashMap<>();

    /**
     * Registers a busy interval for a participant, simulating a connected calendar with an
     * existing commitment.
     *
     * @param participantRef the participant's raw identifier (e-mail)
     * @param busyStart      start of the busy interval
     * @param busyEnd        end of the busy interval
     */
    public void registerBusyPeriod(final String participantRef, final Instant busyStart, final Instant busyEnd) {
        busyPeriodsByParticipant
                .computeIfAbsent(participantRef, ignored -> new ArrayList<>())
                .add(new BusyPeriod(busyStart, busyEnd));
    }

    /**
     * Clears every registered busy period — intended for test teardown between independent
     * scenarios sharing the same Spring context.
     */
    public void reset() {
        busyPeriodsByParticipant.clear();
    }

    /**
     * {@inheritDoc}
     *
     * <p>A participant with no registered busy period at all is available by default, per the
     * interface contract.
     */
    @Override
    public boolean isAvailable(final String participantRef, final Instant slotStart, final Instant slotEnd) {
        List<BusyPeriod> busyPeriods = busyPeriodsByParticipant.get(participantRef);
        if (busyPeriods == null || busyPeriods.isEmpty()) {
            return true;
        }
        for (BusyPeriod busyPeriod : busyPeriods) {
            if (busyPeriod.overlaps(slotStart, slotEnd)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A single busy interval.
     *
     * @param start busy interval start
     * @param end   busy interval end
     */
    private record BusyPeriod(Instant start, Instant end) {

        /**
         * Returns whether {@code [slotStart, slotEnd)} overlaps this busy interval.
         *
         * @param slotStart candidate slot start
         * @param slotEnd   candidate slot end
         * @return {@code true} if the intervals overlap
         */
        boolean overlaps(final Instant slotStart, final Instant slotEnd) {
            return slotStart.isBefore(end) && start.isBefore(slotEnd);
        }
    }
}
