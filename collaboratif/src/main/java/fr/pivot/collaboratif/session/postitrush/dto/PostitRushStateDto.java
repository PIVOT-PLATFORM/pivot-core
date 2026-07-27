package fr.pivot.collaboratif.session.postitrush.dto;

import java.util.List;
import java.util.UUID;

/**
 * Reconnect snapshot for {@code GET .../postit-rush/state} (US47.2.1) — hydrates a rejoining
 * participant exactly once: remaining time, currently-live post-its, and their own score/combo.
 * Fetching this never double-counts prior clicks — it is a pure read, no state mutation.
 *
 * @param roundActive     whether a round is currently active
 * @param roundId         the active round's id, or {@code null} if none
 * @param remainingSeconds seconds remaining in the round (server-computed), or {@code null} if none
 * @param livePostits     currently-live post-its
 * @param myScore         the caller's own cumulative score this round
 * @param myCurrentCombo  the caller's own current combo streak
 * @param myBestCombo     the caller's own best combo streak this round
 * @param myHits          the caller's own hit count this round
 */
public record PostitRushStateDto(
        boolean roundActive,
        UUID roundId,
        Integer remainingSeconds,
        List<LivePostitDto> livePostits,
        int myScore,
        int myCurrentCombo,
        int myBestCombo,
        int myHits) {
}
