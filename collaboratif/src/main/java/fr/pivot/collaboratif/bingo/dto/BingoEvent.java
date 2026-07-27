package fr.pivot.collaboratif.bingo.dto;

import java.util.UUID;

/**
 * Broadcast on {@code /topic/collaboratif/bingo/{roomId}} the moment a bingo is detected
 * (US47.1.1, AC-47.1.1-10) — carries the winner's identity and which of the 12 combinations
 * completed, never the winner's full grid disposition (SEC-04).
 *
 * @param type          always {@code "BINGO"}
 * @param roomId        the room's id
 * @param participantId the winner's grid id
 * @param displayName   the winner's display name (already validated/sanitized, SEC-05)
 * @param line          the winning combination
 */
public record BingoEvent(String type, UUID roomId, UUID participantId, String displayName, LineDto line) {

    /**
     * Builds the event, defaulting {@code type} to {@code "BINGO"}.
     *
     * @param roomId        the room's id
     * @param participantId the winner's grid id
     * @param displayName   the winner's display name
     * @param line          the winning combination
     * @return the built event
     */
    public static BingoEvent of(
            final UUID roomId, final UUID participantId, final String displayName, final LineDto line) {
        return new BingoEvent("BINGO", roomId, participantId, displayName, line);
    }
}
