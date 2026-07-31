package fr.pivot.collaboratif.meetops.availability;

import java.time.Instant;

/**
 * Contract for querying a participant's aggregated free/busy status over a candidate slot
 * (US12.4.1). The producer of this data (calendar/absence connectors, EN22.3) is out of scope for
 * this sprint — see {@link InMemoryAvailabilityAdapter}, the stub implementation used until
 * EN22.3 ships.
 *
 * <p><strong>RGPD — agrégat only (US12.4.1 AC).</strong> Implementations must only ever expose a
 * coarse free/busy boolean — never a calendar event's title, attendees, location, or an absence's
 * motive. {@link #isAvailable} is the entire surface on purpose: there is no method here that
 * could leak more than that.
 */
public interface AvailabilityPort {

    /**
     * Returns whether {@code participantRef} is free for the given candidate slot.
     *
     * <p>A participant with no connected calendar/absence source is considered available by
     * default (US12.4.1 AC) — implementations must return {@code true} for an unknown {@code
     * participantRef}, not throw or return a "no data" sentinel that a caller could mishandle as
     * unavailable.
     *
     * @param participantRef the participant's raw identifier (e-mail)
     * @param slotStart      candidate slot start
     * @param slotEnd        candidate slot end
     * @return {@code true} if free (or no data connected), {@code false} if busy
     */
    boolean isAvailable(String participantRef, Instant slotStart, Instant slotEnd);
}
