package fr.pivot.collaboratif.session.quiz.dto;

import java.util.UUID;

/**
 * One row of a QUIZ leaderboard (US19.3.1).
 *
 * @param participantId the session-scoped participant id (lets a client highlight its own row)
 * @param displayName   the participant's display name
 * @param score         the participant's cumulative score
 */
public record LeaderboardEntry(UUID participantId, String displayName, int score) {
}
