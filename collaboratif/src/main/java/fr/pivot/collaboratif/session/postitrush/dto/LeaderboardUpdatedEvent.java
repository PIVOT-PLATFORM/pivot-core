package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.List;

/**
 * STOMP broadcast {@code LEADERBOARD_UPDATED} (US47.2.1) — throttled to at most every 500ms
 * (widened above softCap, see {@code PostitRushScheduler}), sorted score descending, ties broken
 * by earliest-to-reach-score.
 *
 * @param type    discriminator, always {@code "LEADERBOARD_UPDATED"}
 * @param entries the ranked entries, possibly truncated to a top-N above softCap
 */
public record LeaderboardUpdatedEvent(String type, List<PostitRushLeaderboardEntry> entries) {

    /**
     * Creates the event with its fixed discriminator.
     *
     * @param entries the ranked entries
     */
    public LeaderboardUpdatedEvent(final List<PostitRushLeaderboardEntry> entries) {
        this("LEADERBOARD_UPDATED", entries);
    }
}
