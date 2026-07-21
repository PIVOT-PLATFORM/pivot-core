package fr.pivot.agilite.capacity.dto;

/**
 * Engagement gauge for a capacity event (F11.6.6 — capacity gauge): the committed/engaged points
 * against a reference capacity value, with an overflow flag.
 *
 * <p><strong>Denominator choice.</strong> {@link #referenceEngagement()} is the event's {@code
 * totalEngagementRecommande} (the sum of members' {@code capaciteNette × (1 − margin)}) rather
 * than the raw {@code totalCapaciteNette} — the recommended engagement already bakes in the
 * team's safety margin, so comparing the committed load against it directly answers "are we
 * over the safe commitment line", which is the gauge's purpose. {@link #overflowThreshold()}
 * defaults to {@code 1.0} (i.e. {@link #overCommitted()} flips as soon as {@link
 * #engagedPoints()} exceeds the recommended engagement itself, with no extra slack) — a caller
 * wanting a looser gauge can compare {@link #engagementRatio()} against a different threshold
 * client-side.
 *
 * <p>Does <strong>not</strong> wire the {@code capacity.depassements} KPI counter (Wave 2) —
 * this only exposes the flag/values for a UI gauge.
 *
 * @param engagedPoints        the event's committed points ({@code CapacityEvent#getCommittedPoints()}),
 *                              or {@code 0} if not yet planned
 * @param referenceEngagement  the event's total recommended engagement, in person-days — see the
 *                              denominator-choice note above
 * @param overflowThreshold    the multiplier applied to {@link #referenceEngagement()} above
 *                              which {@link #overCommitted()} flips, default {@code 1.0}
 * @param engagementRatio      {@code engagedPoints / referenceEngagement}, or {@code null} if
 *                              {@link #referenceEngagement()} is {@code 0}
 * @param overCommitted        {@code true} if {@code engagedPoints > overflowThreshold ×
 *                              referenceEngagement}
 */
public record CapacityGaugeResponse(
        double engagedPoints,
        double referenceEngagement,
        double overflowThreshold,
        Double engagementRatio,
        boolean overCommitted) {
}
