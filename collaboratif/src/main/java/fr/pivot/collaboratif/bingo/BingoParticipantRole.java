package fr.pivot.collaboratif.bingo;

/**
 * Role of a Bingo room participant (US47.1.1, AC-47.1.1-13). A {@code PLAYER} owns a persisted
 * {@link BingoGrid} and may mark cells; a {@code SPECTATOR} — admitted once {@code maxPlayers} is
 * reached — never gets a grid row at all and may only observe progress/victory broadcasts.
 */
public enum BingoParticipantRole {
    PLAYER,
    SPECTATOR
}
