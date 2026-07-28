package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.UUID;

/**
 * One ranked row of the POST-IT RUSH live leaderboard (US47.2.1).
 *
 * @param participantId the participant's id
 * @param displayName   the participant's display name
 * @param score         the participant's cumulative score this round
 * @param rank          1-based rank (ties broken by earliest-to-reach-score)
 */
public record PostitRushLeaderboardEntry(UUID participantId, String displayName, int score, int rank) {
}
