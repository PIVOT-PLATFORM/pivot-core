package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.UUID;

/**
 * One ranked row of the final POST-IT RUSH results (US47.2.1).
 *
 * @param rank          1-based final rank
 * @param participantId the participant's id
 * @param displayName   the participant's display name
 * @param score         the final score
 * @param hits          total successful claims
 * @param bestCombo     the best combo streak reached
 */
public record PostitRushStandingEntry(
        int rank, UUID participantId, String displayName, int score, int hits, int bestCombo) {
}
