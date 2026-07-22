package fr.pivot.agilite.exception;

/**
 * Thrown when a capacity event, member, or absence does not exist, belongs to another tenant, or
 * the caller has no link to its team (US11.1.1/US11.2.1/US11.2.2/US11.3.1/US11.4.1/US11.4.2).
 *
 * <p>Using a single exception for every one of these lookups prevents cross-tenant/cross-access
 * information disclosure (the caller cannot distinguish "resource doesn't exist" from "resource
 * exists but you can't see it") — same posture as {@link StandupSessionNotFoundException}/{@link
 * PiNotFoundException}.
 */
public class CapacityNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given resource kind and identifier.
     *
     * @param resource the resource kind, e.g. {@code "capacity event"}, {@code "event member"}
     * @param id       the identifier of the resource that could not be found or accessed
     */
    public CapacityNotFoundException(final String resource, final Object id) {
        super(resource + " not found: " + id);
    }
}
