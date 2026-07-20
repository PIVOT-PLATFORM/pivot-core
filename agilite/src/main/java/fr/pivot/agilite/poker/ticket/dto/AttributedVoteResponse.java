package fr.pivot.agilite.poker.ticket.dto;

/**
 * A single participant's revealed vote, attributed to their roster display name (E09 — classic
 * parity, attributed reveal).
 *
 * <p>Replaces the pre-E09 anonymous {@code values: List<String>} shape: the classic table lets
 * the facilitator ask the extremes to explain their choice, which requires knowing WHO cast a
 * given value at reveal time — masking is still preserved everywhere else (nobody, including the
 * server-broadcast roster, learns any value before this point, see {@code PokerRosterService}).
 *
 * @param name  the voting participant's roster display name, resolved from the room's live roster
 *              at reveal time; a generic placeholder if the participant's roster entry could not
 *              be resolved (e.g. an expired guest session) rather than a null/empty name
 * @param value the participant's cast card value (one of the room's own deck values)
 */
public record AttributedVoteResponse(String name, String value) {
}
