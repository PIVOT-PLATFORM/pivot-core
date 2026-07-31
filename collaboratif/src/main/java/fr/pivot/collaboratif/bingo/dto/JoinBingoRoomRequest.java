package fr.pivot.collaboratif.bingo.dto;

/**
 * Request body for {@code POST /api/collaboratif/bingo/rooms/join} (US47.1.1, AC-47.1.1-02/03).
 *
 * <p>Deliberately carries no Bean Validation constraints: {@code code} and {@code displayName}
 * are validated in {@code BingoRoomService} rather than declaratively, because the rules differ
 * by caller (authenticated vs. anonymous, AC-47.1.1-15/17) and must surface the exact machine
 * codes {@code INVALID_CODE}/{@code INVALID_DISPLAY_NAME} the AC mandates, not a generic Bean
 * Validation message.
 *
 * @param code        the 6-character invite code
 * @param displayName the pseudonym for an anonymous join (2-30 characters); ignored for an
 *                    authenticated join
 */
public record JoinBingoRoomRequest(String code, String displayName) {
}
