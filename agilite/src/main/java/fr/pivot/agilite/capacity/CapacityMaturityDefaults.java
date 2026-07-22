package fr.pivot.agilite.capacity;

import java.util.Map;

/**
 * Pure, persistence-free lookup of the default focus-factor/margin values for each agile-maturity
 * tier (US11.6.4).
 *
 * <p><strong>No direct POC equivalent</strong> (US11.6.4 §Notes) — PouetPouet's {@code capacity}
 * module has no maturity/margin-by-tier concept ({@code focusFactor}/{@code pointsPerPersonDay}
 * are raw parameters there). This table is PIVOT's own, taken directly from {@code
 * EPIC-capacity-planning/README.md}'s §Modèle de calcul default-values table. Deliberately
 * decoupled from JPA/Spring, same posture as {@link CapacityCalculator}.
 */
public final class CapacityMaturityDefaults {

    /** The global default when no team maturity is configured (US11.6.4). */
    public static final Defaults GLOBAL_DEFAULT = new Defaults(70, 15);

    private static final Map<CapacityMaturityLevel, Defaults> BY_LEVEL = Map.of(
            CapacityMaturityLevel.FORMING, new Defaults(60, 20),
            CapacityMaturityLevel.NORMING, new Defaults(70, 10),
            CapacityMaturityLevel.PERFORMING, new Defaults(80, 5));

    private CapacityMaturityDefaults() {
    }

    /**
     * A maturity tier's default focus-factor and margin percentages.
     *
     * @param focusFactorPercent the default focus factor, {@code [10, 100]}
     * @param marginPercent      the default engagement-recommendation margin, {@code [0, 100]}
     */
    public record Defaults(int focusFactorPercent, int marginPercent) {
    }

    /**
     * Resolves the defaults for a maturity tier, or the {@link #GLOBAL_DEFAULT} if {@code
     * maturity} is {@code null} (no team maturity configured).
     *
     * @param maturity the team's maturity tier, or {@code null}
     * @return the tier's defaults, or {@link #GLOBAL_DEFAULT}
     */
    public static Defaults forMaturity(final CapacityMaturityLevel maturity) {
        return maturity == null ? GLOBAL_DEFAULT : BY_LEVEL.get(maturity);
    }
}
