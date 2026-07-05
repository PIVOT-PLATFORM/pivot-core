package fr.pivot.tenant.api;

/**
 * Levée quand une opération super-admin cible un {@code tenantId} qui n'existe pas.
 *
 * <p>Traduite en {@code 404 Not Found} par {@link SuperAdminTenantController}.
 */
public class TenantNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour un tenant introuvable.
     *
     * @param tenantId identifiant du tenant recherché
     */
    public TenantNotFoundException(final Long tenantId) {
        super("Tenant not found: " + tenantId);
    }
}
