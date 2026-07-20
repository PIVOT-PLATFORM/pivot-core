package fr.pivot.agilite.poker.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code VOTES_REVEALED} event broadcast to every participant on {@code
 * /topic/agilite/poker/{roomId}} (US09.2.2) — fired once, at the same instant as the REST reveal
 * response, right after a facilitator reveals a ticket's votes.
 *
 * <p>Carries the same {@code attributedVotes}/{@code consensus} content as {@link RevealResponse}
 * (the REST caller's own response) — simultaneous, identical information for the facilitator and
 * every other room subscriber, never a partial or staggered reveal. Each vote is attributed to
 * the voting participant's roster display name (E09 — classic parity; replaces the pre-E09
 * anonymous {@code values: List<String>} shape) — masking is preserved everywhere else: nobody
 * learns any value before this broadcast fires.
 *
 * <p>Shape figured for the shared room topic alongside {@code TICKET_CREATED}/{@code VOTE_CAST}
 * (US09.2.1) — this event does not modify either of those.
 *
 * @param type            always {@code "VOTES_REVEALED"} — discriminator for the shared room topic
 * @param roomId          the room this ticket belongs to
 * @param ticketId        the revealed ticket's id
 * @param attributedVotes every cast vote's raw value (including {@code "?"}), attributed to the
 *                        voting participant's roster display name, no defined order
 * @param consensus       the computed mean/median/majority
 * @param revealedAt      revelation timestamp
 */
public record VotesRevealedEvent(
        String type, UUID roomId, UUID ticketId, List<AttributedVoteResponse> attributedVotes,
        ConsensusResponse consensus, Instant revealedAt) {

    /** Discriminator value for this event type. */
    public static final String TYPE = "VOTES_REVEALED";

    /**
     * Canonical constructor — defensively copies {@code attributedVotes} into an immutable list
     * so neither the stored field nor the accessor can leak a mutable reference (SpotBugs
     * EI_EXPOSE, same posture as {@code RosterUpdatedEvent}).
     */
    public VotesRevealedEvent {
        attributedVotes = attributedVotes == null ? List.of() : List.copyOf(attributedVotes);
    }

    /**
     * Builds the event with {@link #TYPE} as its discriminator.
     *
     * @param roomId          the room this ticket belongs to
     * @param ticketId        the revealed ticket's id
     * @param attributedVotes every cast vote's raw value, attributed to the voting participant's
     *                        roster display name
     * @param consensus       the computed mean/median/majority
     * @param revealedAt      revelation timestamp
     * @return the constructed event
     */
    public static VotesRevealedEvent of(
            final UUID roomId, final UUID ticketId, final List<AttributedVoteResponse> attributedVotes,
            final ConsensusResponse consensus, final Instant revealedAt) {
        return new VotesRevealedEvent(TYPE, roomId, ticketId, attributedVotes, consensus, revealedAt);
    }
}
