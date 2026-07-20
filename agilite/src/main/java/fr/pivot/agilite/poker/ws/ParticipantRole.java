package fr.pivot.agilite.poker.ws;

/**
 * A planning poker participant's role in a room (E09 — classic parity, named roster).
 *
 * <p>Chosen when joining (or, for the facilitator, derived from {@code facilitatorVotes} at
 * creation). Only a {@link #JOUEUR} casts votes; a {@link #VISITEUR} watches the session (the
 * roster still shows them, but they are never expected to vote and are excluded from the
 * "everyone has voted" denominator).
 */
public enum ParticipantRole {

    /** An estimating participant — casts and validates votes. */
    JOUEUR,

    /** A watch-only participant — present in the roster, never votes. */
    VISITEUR;

    /**
     * Resolves a role identifier leniently: {@code null}/blank/unknown falls back to {@link
     * #JOUEUR} (the safe default — a participant who reached a room is assumed to be estimating
     * unless they explicitly chose to only watch), case-insensitively.
     *
     * @param raw the role identifier from the request, or {@code null}
     * @return the resolved role, never {@code null}
     */
    public static ParticipantRole fromNullable(final String raw) {
        if (raw == null || raw.isBlank()) {
            return JOUEUR;
        }
        try {
            return ParticipantRole.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return JOUEUR;
        }
    }
}
