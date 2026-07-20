package fr.pivot.agilite.poker.ticket.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One revealed ticket's summary within a room's end-of-session {@link RecapResponse} (E09 —
 * classic parity: "une fois la partie terminée, un récapitulatif des résultats sera affiché").
 *
 * <p>Carries the exact same {@code attributedVotes}/{@code consensus} shape as {@link
 * RevealResponse} — a recap entry is simply the durable record of what a past {@code
 * VOTES_REVEALED} already broadcast, never recomputed differently.
 *
 * @param id              the ticket's identifier
 * @param title           the ticket's display title
 * @param revealedAt      when this ticket was revealed
 * @param attributedVotes every cast vote, attributed to the voting participant's roster name
 * @param consensus       the computed mean/median/majority
 */
public record TicketRecapEntry(
        UUID id,
        String title,
        Instant revealedAt,
        List<AttributedVoteResponse> attributedVotes,
        ConsensusResponse consensus) {

    /**
     * Canonical constructor — defensively copies {@code attributedVotes} into an immutable list
     * (SpotBugs EI_EXPOSE, same posture as {@code RevealResponse}/{@code VotesRevealedEvent}).
     */
    public TicketRecapEntry {
        attributedVotes = attributedVotes == null ? List.of() : List.copyOf(attributedVotes);
    }
}
