package fr.pivot.agilite.capacity;

/**
 * The kind of capacity event (US11.1.1).
 *
 * <p>{@code PI_PLANNING}/{@code INCREMENT} (the latter added US11.5.1, Sprint 21) events never
 * carry their own {@link CapacityEventMember} roster — they aggregate capacity from their {@code
 * SPRINT}/{@code RELEASE}/{@code CUSTOM} children instead (US11.3.1). {@code INCREMENT} is a lot
 * of N sprints with no IP-iteration semantics, unlike {@code PI_PLANNING} — see {@link
 * CapacityEvent#isIpIteration()}. Deliberately independent of {@code fr.pivot.agilite.pi.PiCycle}
 * (E50 PI Planning, see {@link CapacityEvent}'s Javadoc) — this is a capacity-planning concept,
 * not a Program Increment cycle.
 */
public enum CapacityEventType {
    PI_PLANNING,
    INCREMENT,
    SPRINT,
    RELEASE,
    CUSTOM
}
