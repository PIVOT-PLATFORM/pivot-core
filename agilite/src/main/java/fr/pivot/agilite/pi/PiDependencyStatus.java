package fr.pivot.agilite.pi;

/**
 * Visual/business status of a {@link PiDependency} between two {@link PiTicket}s (US50.3.2).
 */
public enum PiDependencyStatus {
    /** The dependency is not currently a concern. */
    OK,
    /** The dependency is a known blocker. */
    BLOCKED
}
