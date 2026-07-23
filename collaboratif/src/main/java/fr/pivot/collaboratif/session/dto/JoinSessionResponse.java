package fr.pivot.collaboratif.session.dto;

import java.util.UUID;

/**
 * Response body for {@code POST /api/collaboratif/sessions/join} (US19.2.1).
 *
 * @param participantId the created participant's id
 * @param token         the sealed guest token (only meaningful/present for anonymous joins;
 *                      {@code null} for authenticated joins, which use their own Bearer token)
 * @param wsTopic       the STOMP destination this session's participants subscribe to
 */
public record JoinSessionResponse(UUID participantId, String token, String wsTopic) {
}
