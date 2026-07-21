package fr.pivot.agilite.capacity;

/**
 * Team maturity level of a {@link CapacityEvent} (E11 — capacity planning), driving the default
 * focus-factor/margin/velocity-multiplier profile applied by {@code
 * fr.pivot.agilite.capacity.calc.CapacityCalculator} when the event does not override those
 * values explicitly.
 *
 * <p>A {@code null} {@link CapacityEvent#getMaturityLevel()} (the column is nullable) is a
 * distinct, fourth profile — "unset" — with its own default values; it is deliberately not
 * modeled as a member of this enum so {@code CapacityCalculator}'s maturity lookup stays a plain
 * {@code null}-safe switch rather than needing a synthetic {@code UNSET} constant threaded
 * through every caller.
 */
public enum CapacityMaturityLevel {

    /** Newly formed team — lowest default focus factor, highest default margin. */
    FORMING,

    /** Team finding its rhythm — intermediate default profile. */
    NORMING,

    /** Established, high-performing team — highest default focus factor, lowest default margin. */
    PERFORMING
}
