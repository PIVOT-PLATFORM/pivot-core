package fr.pivot.tenant.api;

/**
 * Levée quand un super admin tente de créer un tenant avec un slug figurant sur la liste des
 * termes réservés de la plateforme — US06.2.1 « Super admin crée un tenant ».
 *
 * <p>Traduite en {@code 422 Unprocessable Entity} par {@link SuperAdminTenantController} — pas
 * {@code 409}, pour distinguer explicitement « ce slug ne sera jamais disponible » (choix à
 * changer côté formulaire) de « ce slug est pris pour l'instant » ({@link
 * TenantSlugAlreadyExistsException}, {@code 409}). Voir {@link TenantSlugPolicy} pour la liste.
 */
public class ReservedTenantSlugException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour un slug réservé.
     *
     * @param slug le slug réservé demandé
     */
    public ReservedTenantSlugException(final String slug) {
        super("Tenant slug is reserved: " + slug);
    }
}
