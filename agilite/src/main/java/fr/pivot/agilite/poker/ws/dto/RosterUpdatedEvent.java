package fr.pivot.agilite.poker.ws.dto;

import java.util.List;
import java.util.UUID;

/**
 * {@code ROSTER_UPDATED} event broadcast to every participant on
 * {@code /topic/agilite/poker/{roomId}} (E09 — classic parity, named roster).
 *
 * <p>Sent whenever the room's roster changes in a way everyone should see: a participant joins,
 * or a participant's vote state on the active ticket flips. It carries the full current roster
 * (not a delta) so a late subscriber that receives one event has the complete picture, mirroring
 * how the board already re-derives its whole state from the latest broadcast. Shares the room
 * topic with {@code VOTE_CAST}/{@code TICKET_CREATED}/{@code VOTES_REVEALED}; consumers switch on
 * {@link #type}.
 *
 * @param type         always {@code "ROSTER_UPDATED"} — the shared-topic discriminator
 * @param roomId       the room whose roster this describes
 * @param participants the room's current participants (masked vote state only — never card values)
 */
public record RosterUpdatedEvent(String type, UUID roomId, List<RosterParticipant> participants) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "ROSTER_UPDATED";

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param roomId       the room whose roster this describes
     * @param participants the room's current participants
     * @return the constructed event
     */
    public static RosterUpdatedEvent of(final UUID roomId, final List<RosterParticipant> participants) {
        return new RosterUpdatedEvent(TYPE, roomId, participants);
    }
}
