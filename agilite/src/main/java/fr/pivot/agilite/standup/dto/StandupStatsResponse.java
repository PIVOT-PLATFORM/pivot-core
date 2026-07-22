package fr.pivot.agilite.standup.dto;

import java.util.List;

/**
 * Response payload for {@code GET /standup/stats} (US10.3.1): a team's completed sessions and
 * per-participant aggregated speaking stats over the requested period.
 *
 * @param sessions     the team's {@code DONE} sessions in the period, {@code startedAt}
 *                      descending
 * @param participants per-participant aggregated stats over the period, total speaking time
 *                      descending
 */
public record StandupStatsResponse(
        List<StandupSessionStatsEntry> sessions, List<StandupParticipantStatsEntry> participants) {
}
