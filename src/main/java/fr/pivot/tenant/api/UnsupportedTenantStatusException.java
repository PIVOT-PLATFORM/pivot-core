package fr.pivot.tenant.api;

/**
 * Levée quand {@code PATCH /api/superadmin/tenants/{tenantId}/status} reçoit une valeur de
 * {@code status} autre que {@link TenantStatusRequest#INACTIVE} (US06.2.2 — seule la
 * désactivation est implémentée par cette US).
 *
 * <p>Traduite en {@code 400 Bad Request} par {@link SuperAdminTenantController}.
 */
public class UnsupportedTenantStatusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour une valeur de statut non supportée.
     *
     * @param status valeur brute reçue dans le corps de la requête
     */
    public UnsupportedTenantStatusException(final String status) {
        super("Unsupported tenant status: " + status);
    }
}
