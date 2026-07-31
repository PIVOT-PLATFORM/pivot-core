package fr.pivot.collaboratif.bingo.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response shape shared by create ({@code POST .../rooms}), authenticated join and anonymous join
 * (US47.1.1, AC-47.1.1-01/02/03) — every field is present on all three; only {@code code} is
 * omitted from the join responses (only the creator ever needs the code again to share it).
 *
 * @param roomId      the room's id
 * @param code        the 6-character invite code — present on create, {@code null} on join
 * @param name        the room's display name
 * @param status      {@code OPEN} or {@code FINISHED}
 * @param maxPlayers  the configured player threshold before spectator degradation
 * @param expiresAt   the room's expiry timestamp
 * @param wsTopic     the STOMP broadcast topic to subscribe to
 * @param accessToken the caller's opaque, room-scoped WebSocket access grant (SEC-03)
 * @param role        {@code PLAYER} or {@code SPECTATOR}
 * @param grid        the caller's own grid, or {@code null} for a spectator
 */
public record BingoRoomResponse(
        UUID roomId,
        String code,
        String name,
        String status,
        int maxPlayers,
        Instant expiresAt,
        String wsTopic,
        String accessToken,
        String role,
        GridDto grid) {
}
