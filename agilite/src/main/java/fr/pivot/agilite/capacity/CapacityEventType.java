package fr.pivot.agilite.capacity;

/**
 * The kind of capacity event (US11.1.1).
 *
 * <p>{@code PI_PLANNING} events never carry their own {@link CapacityEventMember} roster — they
 * aggregate capacity from their {@code SPRINT}/{@code RELEASE}/{@code CUSTOM} children instead
 * (US11.3.1). Deliberately independent of {@code fr.pivot.agilite.pi.PiCycle} (E50 PI Planning,
 * see {@link CapacityEvent}'s Javadoc) — this is a capacity-planning concept, not a Program
 * Increment cycle.
 */
public enum CapacityEventType {
    PI_PLANNING,
    SPRINT,
    RELEASE,
    CUSTOM
}
