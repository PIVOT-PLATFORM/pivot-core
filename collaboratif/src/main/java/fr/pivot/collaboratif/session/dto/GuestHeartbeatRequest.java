package fr.pivot.collaboratif.session.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/collaboratif/sessions/{id}/participants/{participantId}/heartbeat}
 * (US19.2.1, guest participants only) — refreshes the guest token's rolling TTL.
 *
 * @param token the guest token issued by {@code POST /sessions/join}
 */
public record GuestHeartbeatRequest(
        @NotBlank(message = "INVALID_TOKEN")
        String token) {
}
