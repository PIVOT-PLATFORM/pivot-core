package fr.pivot.agilite.poker.ws.dto;

import fr.pivot.agilite.poker.ws.ParticipantRole;

/**
 * One entry of a room's live participant roster, as broadcast to every subscriber (E09 — classic
 * parity, named roster).
 *
 * <p>Carries only what the classic table shows — the participant's chosen display {@code name},
 * their {@code role}, and whether they have voted on the currently active ticket ({@code
 * hasVoted}). It deliberately never carries the participant's access token nor its hash: identity
 * correlation stays server-side. {@code hasVoted} is the only vote-related signal exposed while a
 * ticket is still open — the chosen card value is never included here (masked until reveal).
 *
 * @param name     the participant's chosen display name
 * @param role     the participant's role ({@link ParticipantRole})
 * @param hasVoted whether this participant has recorded a vote on the active ticket (always
 *                 {@code false} for a {@link ParticipantRole#VISITEUR} and when no ticket is open)
 */
public record RosterParticipant(String name, ParticipantRole role, boolean hasVoted) {
}
