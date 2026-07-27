package fr.pivot.collaboratif.bingo.dto;

import java.util.UUID;

/**
 * Broadcast on {@code /topic/collaboratif/bingo/{roomId}} when a participant joins (US47.1.1,
 * AC-47.1.1-06). {@code displayName} has already been validated/sanitized server-side (SEC-05)
 * before this event is built — the frontend renders it as plain text regardless.
 *
 * @param type           always {@code "PARTICIPANT_JOINED"}
 * @param roomId         the room's id
 * @param participantId  the joining participant's grid id (or a spectator's ephemeral id)
 * @param displayName    the joining participant's resolved display name
 * @param playerCount    current number of PLAYER participants
 * @param spectatorCount current number of SPECTATOR participants
 */
public record ParticipantJoinedEvent(
        String type, UUID roomId, UUID participantId, String displayName, int playerCount, int spectatorCount) {

    /**
     * Builds the event, defaulting {@code type} to {@code "PARTICIPANT_JOINED"}.
     *
     * @param roomId         the room's id
     * @param participantId  the joining participant's id
     * @param displayName    the joining participant's resolved display name
     * @param playerCount    current number of PLAYER participants
     * @param spectatorCount current number of SPECTATOR participants
     * @return the built event
     */
    public static ParticipantJoinedEvent of(
            final UUID roomId, final UUID participantId, final String displayName,
            final int playerCount, final int spectatorCount) {
        return new ParticipantJoinedEvent(
                "PARTICIPANT_JOINED", roomId, participantId, displayName, playerCount, spectatorCount);
    }
}
