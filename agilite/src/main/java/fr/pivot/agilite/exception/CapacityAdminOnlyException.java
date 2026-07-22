package fr.pivot.agilite.exception;

/**
 * Thrown when a caller who is not a tenant administrator attempts to manage the tenant's holiday
 * list (US11.6.1) — a tenant-configuration resource, not a team resource, so this is the one
 * place in the capacity module where HTTP 403 is the correct response instead of the usual 404
 * anti-enumeration convention (there is nothing to hide: the caller already knows their own
 * tenant has holiday configuration, they simply lack the role to change it).
 */
public class CapacityAdminOnlyException extends RuntimeException {

    /**
     * Creates the exception with a fixed, non-parameterized message.
     */
    public CapacityAdminOnlyException() {
        super("Tenant administrator role required");
    }
}
