package fr.pivot.collaboratif.session.dto;

import tools.jackson.databind.JsonNode;
import fr.pivot.collaboratif.session.SessionStatus;
import fr.pivot.collaboratif.session.SessionType;

import java.time.Instant;
import java.util.UUID;

/**
 * API response shape for a session (US19.1.1) — returned by create, get, and list.
 *
 * @param id              session id
 * @param title           session title
 * @param type            fixed activity type
 * @param status          lifecycle status
 * @param joinCode        6-character join code
 * @param config          type-dependent configuration
 * @param teamId          optional owning team id, or {@code null}
 * @param participantCount current number of participants
 * @param createdAt       creation timestamp
 * @param startedAt       first-LIVE timestamp, or {@code null}
 * @param endedAt         completion timestamp, or {@code null}
 */
public record SessionResponse(
        UUID id,
        String title,
        SessionType type,
        SessionStatus status,
        String joinCode,
        JsonNode config,
        Long teamId,
        long participantCount,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt) {
}
