package fr.pivot.agilite.capacity;

/**
 * Kind of {@link CapacityEvent} (E11 — capacity planning).
 *
 * <p>Ported from the PouetPouet POC's {@code CapacityEventType} (apps/web's {@code capacity.ts}).
 */
public enum CapacityEventType {

    /** SAFe Program Increment planning event — typically a parent of several {@link #SPRINT}s. */
    PI_PLANNING,

    /** A single sprint — the usual leaf-level capacity event. */
    SPRINT,

    /** A release, not necessarily aligned with a sprint boundary. */
    RELEASE,

    /** Any other capacity-tracked event that does not fit the three types above. */
    CUSTOM
}
