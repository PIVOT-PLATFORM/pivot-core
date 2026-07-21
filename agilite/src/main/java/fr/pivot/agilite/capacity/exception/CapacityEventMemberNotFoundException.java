package fr.pivot.agilite.capacity.exception;

import java.util.UUID;

/**
 * Thrown when a capacity event member cannot be found for the caller's tenant (E11) — either
 * because no member exists with the given id, or because it belongs to an event owned by a
 * different tenant. Both cases are deliberately indistinguishable (mapped to HTTP 404 by
 * {@code CapacityExceptionHandler}) to avoid confirming cross-tenant existence — see the
 * transversal tenant-isolation rule in this repo's {@code CLAUDE.md}.
 */
public class CapacityEventMemberNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for the given capacity event member id.
     *
     * @param memberId the member id that could not be found for the caller's tenant
     */
    public CapacityEventMemberNotFoundException(final UUID memberId) {
        super("Capacity event member not found: " + memberId);
    }
}
