package fr.pivot.collaboratif.session.postitrush;

import java.time.Duration;

/**
 * Fixed game constants for the POST-IT RUSH activity type (US47.2.1 finalized AC defaults).
 */
public final class PostitRushConstants {

    /** Default round duration when the session config does not override it. */
    public static final int DEFAULT_DURATION_SECONDS = 90;

    /** Minimum random post-it lifespan in milliseconds. */
    public static final int LIFESPAN_MIN_MS = 1200;

    /** Maximum random post-it lifespan in milliseconds. */
    public static final int LIFESPAN_MAX_MS = 2500;

    /** Base points awarded per hit, before the combo multiplier. */
    public static final int BASE_POINTS = 10;

    /** Leaderboard broadcast throttle floor — at most one broadcast per window below softCap. */
    public static final Duration LEADERBOARD_THROTTLE = Duration.ofMillis(500);

    /**
     * Leaderboard broadcast throttle above softCap — progressive degradation widens the window
     * rather than ever hard-blocking a joiner (AC: "NEVER a brutal block or timed lockout").
     */
    public static final Duration LEADERBOARD_THROTTLE_DEGRADED = Duration.ofMillis(1500);

    /** Leaderboard truncation above softCap — top-N only, to bound broadcast payload size. */
    public static final int LEADERBOARD_TOP_N_DEGRADED = 20;

    /** Active-player threshold above which progressive degradation kicks in. */
    public static final int SOFT_CAP = 50;

    /** Hard participant capacity — new joiners past this become SPECTATOR/queue, never blocked. */
    public static final int HARD_CAP = 200;

    /**
     * Gap range between consecutive spawns below softCap — an implementation detail not fixed by
     * the AC (only the 1200-2500ms lifespan is), chosen to keep the board lively without spawning
     * so densely the softCap degradation and the 500ms leaderboard throttle become meaningless.
     */
    public static final int SPAWN_GAP_MIN_MS = 400;

    /** Upper bound of the below-softCap spawn gap range. */
    public static final int SPAWN_GAP_MAX_MS = 1100;

    /** Widened spawn gap range above softCap — fewer concurrent live post-its, batched load. */
    public static final int SPAWN_GAP_MIN_MS_DEGRADED = 900;

    /** Upper bound of the above-softCap spawn gap range. */
    public static final int SPAWN_GAP_MAX_MS_DEGRADED = 2000;

    /**
     * Non-color visual identity keys a spawn is randomly assigned (WCAG 1.4.1). Package-protected
     * (not {@code public}) — SpotBugs {@code MS_PKGPROTECT}: a mutable array constant must never be
     * {@code public} (a caller could mutate the shared backing array); only
     * {@link PostitRushActivityService}, in the same package, reads it.
     */
    static final String[] COLOR_KEYS = {"amber", "rose", "sky", "lime", "violet", "teal"};

    /** Maximum plausible human clicks per second before a participant is rate-limited (429). */
    public static final int CLICK_RATE_LIMIT_PER_SECOND = 8;

    private PostitRushConstants() {
    }
}
