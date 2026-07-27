package fr.pivot.collaboratif.session;

/**
 * A {@link Participant}'s role within a session (US47.2.1).
 *
 * <p>{@code PLAYER} for every ordinary session type (the only role that existed before
 * US47.2.1). {@code SPECTATOR} is assigned only when a {@code POSTIT_RUSH} room has reached its
 * hard capacity and the caller explicitly accepted the spectator fallback offered on the prior
 * {@code 409 ROOM_FULL} response — never a silent downgrade.
 */
public enum ParticipantRole {
    PLAYER,
    SPECTATOR
}
