package fr.pivot.collaboratif.bingo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/collaboratif/bingo/rooms} (US47.1.1, AC-47.1.1-01).
 *
 * @param name the room's display name (1-80 characters)
 */
public record CreateBingoRoomRequest(
        @NotBlank @Size(min = 1, max = 80) String name) {
}
