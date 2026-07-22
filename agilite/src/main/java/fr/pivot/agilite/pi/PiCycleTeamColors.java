package fr.pivot.agilite.pi;

/**
 * Fixed display-color palette for {@link PiCycleTeam} rows (US50.1.1).
 *
 * <p>{@code public.teams} has no color column, so there is nothing to copy at import time —
 * every Train team (imported or manually added, when the caller omits {@code color}) is
 * server-assigned a color cycling through this fixed palette by {@code teamOrder}.
 */
final class PiCycleTeamColors {

    private static final String[] PALETTE = {
        "#4F46E5", "#059669", "#D97706", "#DC2626", "#7C3AED",
        "#0891B2", "#DB2777", "#65A30D", "#EA580C", "#4338CA",
    };

    private PiCycleTeamColors() {
    }

    /**
     * Returns the palette color for a given display order, cycling through the fixed palette.
     *
     * @param order the team's {@code teamOrder} (0-based)
     * @return a hex color string
     */
    static String forOrder(final int order) {
        return PALETTE[Math.floorMod(order, PALETTE.length)];
    }
}
