package fr.pivot.agilite.exception;

import java.util.UUID;

/**
 * Thrown when a PI cycle, iteration, Train team, ticket, or dependency does not exist, belongs
 * to another tenant, or the caller has no link to its cycle (US50.1.1/US50.3.1/US50.3.2).
 *
 * <p>Using a single exception for every one of these lookups prevents cross-tenant/cross-access
 * information disclosure (the caller cannot distinguish "resource doesn't exist" from "resource
 * exists but you can't see it") — same posture as {@link StandupSessionNotFoundException}/{@link
 * WheelNotFoundException}.
 */
public class PiNotFoundException extends RuntimeException {

    /**
     * Creates a not-found exception for the given resource kind and identifier.
     *
     * @param resource the resource kind, e.g. {@code "PI cycle"}, {@code "Train team"}
     * @param id       the UUID of the resource that could not be found or accessed
     */
    public PiNotFoundException(final String resource, final UUID id) {
        super(resource + " not found: " + id);
    }
}
