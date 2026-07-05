package fr.pivot.tenant.api;

/**
 * Levée quand un super admin tente de désactiver le tenant système — celui hébergeant les
 * comptes {@code ROLE_SUPER_ADMIN} de la plateforme (US06.2.2).
 *
 * <p>Désactiver ce tenant révoquerait en masse les sessions de tous les super admins,
 * y compris potentiellement celle de l'appelant, rendant la plateforme inadministrable.
 * Identifié par le slug configurable {@code pivot.tenant.system-tenant-slug} (jamais un
 * identifiant numérique en dur) — voir {@link SuperAdminTenantService}.
 *
 * <p>Traduite en {@code 403 Forbidden} avec un message explicite par
 * {@link SuperAdminTenantController}.
 */
public class SystemTenantProtectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour une tentative de désactivation du tenant système.
     *
     * @param tenantId identifiant du tenant système dont la désactivation a été refusée
     */
    public SystemTenantProtectedException(final Long tenantId) {
        super("System tenant cannot be deactivated: " + tenantId);
    }
}
