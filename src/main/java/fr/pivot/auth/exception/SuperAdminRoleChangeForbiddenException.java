package fr.pivot.auth.exception;

/**
 * Levée quand un administrateur tenant tente de modifier le rôle d'un utilisateur dont le rôle
 * actuel est {@code ROLE_SUPER_ADMIN}, via {@code PATCH /api/admin/users/{userId}/role}
 * (US06.1.3).
 *
 * <p>{@code ROLE_SUPER_ADMIN} est un rôle plateforme, hors périmètre d'un admin tenant (voir
 * CLAUDE.md « Schéma de rôles ») — même quand le compte super-admin réside, en base, dans le même
 * tenant que l'administrateur appelant (le « tenant système » qui héberge les comptes
 * super-admin, voir {@code SuperAdminTenantService#isSystemTenant}). Sans cette garde, un simple
 * {@code ROLE_ADMIN} du tenant système pourrait rétrograder un {@code ROLE_SUPER_ADMIN} en
 * {@code ROLE_USER} par ce endpoint tenant, et lui faire perdre tous ses droits plateforme — un
 * chemin de désescalade de privilèges non couvert par la seule vérification d'auto-modification
 * ({@link SelfRoleChangeForbiddenException}). Traduite en {@code 403 Forbidden} par
 * {@code AdminUserController}.
 */
public class SuperAdminRoleChangeForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour une tentative de modification du rôle d'un compte
     * {@code ROLE_SUPER_ADMIN} via cet endpoint tenant.
     *
     * @param userId identifiant de l'utilisateur ciblé, dont le rôle actuel est
     *               {@code ROLE_SUPER_ADMIN}
     */
    public SuperAdminRoleChangeForbiddenException(final Long userId) {
        super("Tenant admin cannot change a platform super-admin's role: " + userId);
    }
}
