package fr.pivot.agilite.capacity;

/**
 * Lifecycle status of a {@link CapacityEvent} (E11 — capacity planning).
 *
 * <p>Ported from the PouetPouet POC's {@code CapacityEventStatus} (apps/web's {@code capacity.ts}).
 * No strict state machine is enforced by this Wave 0 — free transitions are a later wave's
 * (the service layer's) responsibility.
 */
public enum CapacityEventStatus {

    /** Not started yet — the default status on creation. */
    PLANNING,

    /** Currently in progress. */
    ACTIVE,

    /** Finished — eligible as history for {@code CapacityCalculator}'s velocity forecast. */
    DONE
}
