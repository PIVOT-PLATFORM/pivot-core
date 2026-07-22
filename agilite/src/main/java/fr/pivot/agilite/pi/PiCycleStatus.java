package fr.pivot.agilite.pi;

/**
 * Lifecycle status of a {@link PiCycle} (US50.1.1).
 *
 * <p>No strict state machine at the socle — {@code PATCH .../cycles/{id}} lets the caller
 * transition freely between the three values (AC explicitly rules out enforcing a fixed order).
 */
public enum PiCycleStatus {
    /** The PI is being prepared — the default status at creation. */
    PREPARATION,
    /** The PI is currently running. */
    ACTIVE,
    /** The PI has been closed. */
    CLOSED
}
