package fr.pivot.collaboratif.exception;

/**
 * Thrown when the MeetOps module is disabled for the caller's tenant (US12.1.1 AC8).
 *
 * <p>Mapped to HTTP 403 Forbidden by {@link CollaboratifExceptionHandler}.
 */
public class MeetOpsModuleDisabledException extends RuntimeException {

    /**
     * Creates a module-disabled exception for the given tenant.
     *
     * @param tenantId the {@code public.tenants.id} of the tenant for which the MeetOps
     *                 module is inactive
     */
    public MeetOpsModuleDisabledException(final Long tenantId) {
        super("MeetOps module is disabled for tenant: " + tenantId);
    }
}
