package fr.pivot.collaboratif.bingo.dto;

import java.util.UUID;

/**
 * Response shape for {@code GET /api/collaboratif/bingo/rooms/{roomId}/grid} (US47.1.1,
 * AC-47.1.1-05) — lets a reconnecting participant re-display the game without generating a new
 * grid.
 *
 * @param roomId the room's id
 * @param status {@code OPEN} or {@code FINISHED}
 * @param role   {@code PLAYER} or {@code SPECTATOR}
 * @param grid   the caller's own grid with its current marked state, or {@code null} for a
 *               spectator
 */
public record BingoGridResponse(UUID roomId, String status, String role, GridDto grid) {
}
