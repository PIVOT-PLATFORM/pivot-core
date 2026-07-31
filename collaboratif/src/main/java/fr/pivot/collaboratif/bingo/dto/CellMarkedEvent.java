package fr.pivot.collaboratif.bingo.dto;

import java.util.UUID;

/**
 * Broadcast on {@code /topic/collaboratif/bingo/{roomId}} whenever a participant marks/unmarks a
 * cell (US47.1.1, AC-47.1.1-07). <strong>Never carries {@code cellIndex} or the phrase</strong>
 * (AC-47.1.1-08/SEC-04) — only the aggregate {@code markedCount}, so other participants can build
 * a "who has marked how many cells" progress table without ever learning another participant's
 * disposition or which specific phrases they heard.
 *
 * @param type          always {@code "CELL_MARKED"}
 * @param roomId        the room's id
 * @param participantId the marking participant's grid id
 * @param markedCount   the marking participant's current total marked-cell count
 */
public record CellMarkedEvent(String type, UUID roomId, UUID participantId, int markedCount) {

    /**
     * Builds the event, defaulting {@code type} to {@code "CELL_MARKED"}.
     *
     * @param roomId        the room's id
     * @param participantId the marking participant's id
     * @param markedCount   the marking participant's current total marked-cell count
     * @return the built event
     */
    public static CellMarkedEvent of(final UUID roomId, final UUID participantId, final int markedCount) {
        return new CellMarkedEvent("CELL_MARKED", roomId, participantId, markedCount);
    }
}
