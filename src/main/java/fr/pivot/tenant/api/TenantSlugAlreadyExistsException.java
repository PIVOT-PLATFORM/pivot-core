package fr.pivot.tenant.api;

/**
 * Levée quand un super admin tente de créer un tenant avec un slug déjà utilisé par un autre
 * tenant — US06.2.1 « Super admin crée un tenant ».
 *
 * <p>Traduite en {@code 409 Conflict} par {@link SuperAdminTenantController}. Distincte de
 * {@link ReservedTenantSlugException} ({@code 422}) : ici le slug est syntaxiquement valide et
 * non réservé, mais déjà pris par une ligne existante de {@code tenants}.
 */
public class TenantSlugAlreadyExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour un slug déjà utilisé.
     *
     * @param slug le slug en conflit
     */
    public TenantSlugAlreadyExistsException(final String slug) {
        super("Tenant slug already exists: " + slug);
    }
}
