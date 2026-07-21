package fr.pivot.agilite.capacity.exception;

import java.util.UUID;

/**
 * Thrown when a capacity absence cannot be found for the caller's tenant (E11) — either because
 * no absence exists with the given id, or because it belongs to an event owned by a different
 * tenant. Both cases are deliberately indistinguishable (mapped to HTTP 404 by
 * {@code CapacityExceptionHandler}) to avoid confirming cross-tenant existence — see the
 * transversal tenant-isolation rule in this repo's {@code CLAUDE.md}.
 */
public class CapacityAbsenceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception for the given capacity absence id.
     *
     * @param absenceId the absence id that could not be found for the caller's tenant
     */
    public CapacityAbsenceNotFoundException(final UUID absenceId) {
        super("Capacity absence not found: " + absenceId);
    }
}
