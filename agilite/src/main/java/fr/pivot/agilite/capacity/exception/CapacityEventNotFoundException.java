package fr.pivot.agilite.capacity.exception;

import java.util.UUID;

/**
 * Thrown when a capacity event cannot be found for the caller's tenant (E11) — either because no
 * event exists with the given id, or because it belongs to a different tenant. Both cases are
 * deliberately indistinguishable (mapped to HTTP 404 by {@code CapacityExceptionHandler}) to
 * avoid confirming cross-tenant existence — see the transversal tenant-isolation rule in this
 * repo's {@code CLAUDE.md}.
 */
public class CapacityEventNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for the given capacity event id.
     *
     * @param eventId the capacity event id that could not be found for the caller's tenant
     */
    public CapacityEventNotFoundException(final UUID eventId) {
        super("Capacity event not found: " + eventId);
    }
}
