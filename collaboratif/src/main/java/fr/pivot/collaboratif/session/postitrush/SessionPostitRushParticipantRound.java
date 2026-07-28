package fr.pivot.collaboratif.session.postitrush;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A participant's mutable score/combo state within one {@link SessionPostitRushRound} (US47.2.1).
 *
 * <p>Combo ladder (server-computed, {@link #registerHit}): ×1 for hits 1-2, ×2 for 3-5, ×3 for
 * 6+. {@link #registerMiss()} resets {@link #currentCombo} to zero without touching {@link
 * #score}/{@link #hits}/{@link #bestCombo} — a miss costs the streak, never already-banked points.
 */
@Entity
@Table(name = "session_postit_rush_participant_round", schema = "collaboratif")
public class SessionPostitRushParticipantRound {

    private static final int BASE_POINTS = 10;
    private static final int COMBO_TIER_2_THRESHOLD = 3;
    private static final int COMBO_TIER_3_THRESHOLD = 6;

    /** Composite primary key: (roundId, participantId). */
    @EmbeddedId
    private SessionPostitRushParticipantRoundId id;

    /** Cumulative score for this round, server-computed only. */
    @Column(name = "score", nullable = false)
    private int score;

    /** Current consecutive-hit streak; reset to zero on any miss. */
    @Column(name = "current_combo", nullable = false)
    private int currentCombo;

    /** Highest streak reached this round; monotonic, never decreases. */
    @Column(name = "best_combo", nullable = false)
    private int bestCombo;

    /** Total successful claims this round. */
    @Column(name = "hits", nullable = false)
    private int hits;

    /** Instant {@link #score} last changed — the leaderboard's earliest-to-reach tie-break. */
    @Column(name = "score_reached_at")
    private Instant scoreReachedAt;

    /** No-arg constructor required by JPA. */
    protected SessionPostitRushParticipantRound() {
    }

    /**
     * Creates a fresh, zeroed row for a participant entering a round.
     *
     * @param roundId       the owning round's UUID
     * @param participantId the participant's UUID
     */
    public SessionPostitRushParticipantRound(final UUID roundId, final UUID participantId) {
        this.id = new SessionPostitRushParticipantRoundId(roundId, participantId);
    }

    /**
     * Returns the multiplier for a given streak length (hits 1-2 → ×1, 3-5 → ×2, 6+ → ×3).
     *
     * @param streak the 1-based streak length (the hit count including this one)
     * @return the multiplier
     */
    public static int multiplierFor(final int streak) {
        if (streak >= COMBO_TIER_3_THRESHOLD) {
            return 3;
        }
        if (streak >= COMBO_TIER_2_THRESHOLD) {
            return 2;
        }
        return 1;
    }

    /**
     * Records a successful claim — increments the combo streak, awards
     * {@code basePoints × multiplier(streak)}, and refreshes the tie-break timestamp.
     *
     * @param now the instant of the hit
     * @return the points just awarded
     */
    public int registerHit(final Instant now) {
        currentCombo++;
        bestCombo = Math.max(bestCombo, currentCombo);
        hits++;
        int points = BASE_POINTS * multiplierFor(currentCombo);
        score += points;
        scoreReachedAt = now;
        return points;
    }

    /**
     * Records a miss (stale click or an unclaimed expiry attributed to this participant) —
     * resets the combo streak to zero. Score, hits and bestCombo are untouched.
     */
    public void registerMiss() {
        currentCombo = 0;
    }

    /**
     * Returns the composite primary key.
     *
     * @return the id
     */
    public SessionPostitRushParticipantRoundId getId() {
        return id;
    }

    /**
     * Returns the round id component of the key.
     *
     * @return the round UUID
     */
    public UUID getRoundId() {
        return id.getRoundId();
    }

    /**
     * Returns the participant id component of the key.
     *
     * @return the participant UUID
     */
    public UUID getParticipantId() {
        return id.getParticipantId();
    }

    /**
     * Returns the cumulative score.
     *
     * @return the score
     */
    public int getScore() {
        return score;
    }

    /**
     * Returns the current combo streak.
     *
     * @return the current combo
     */
    public int getCurrentCombo() {
        return currentCombo;
    }

    /**
     * Returns the best combo streak reached this round.
     *
     * @return the best combo
     */
    public int getBestCombo() {
        return bestCombo;
    }

    /**
     * Returns the number of successful claims.
     *
     * @return the hit count
     */
    public int getHits() {
        return hits;
    }

    /**
     * Returns when the score last changed.
     *
     * @return the scoreReachedAt instant, or {@code null} before any hit
     */
    public Instant getScoreReachedAt() {
        return scoreReachedAt;
    }
}
