package fr.pivot.auth.dto;

/**
 * Rôles qu'un administrateur tenant peut assigner à un utilisateur via
 * {@code PATCH /api/admin/users/{userId}/role} (US06.1.3 « Admin modifie le rôle d'un
 * utilisateur »).
 *
 * <p>Volontairement fermé à {@link #ROLE_ADMIN} et {@link #ROLE_USER} — {@code ROLE_SUPER_ADMIN}
 * (rôle plateforme, hors périmètre d'un admin tenant) et {@code ROLE_GUEST} (rôle de session,
 * jamais assigné explicitement) sont exclus par construction. Utilisé comme type de champ direct
 * dans {@link UpdateUserRoleRequest} : toute valeur JSON hors de cette énumération (y compris
 * {@code "ROLE_SUPER_ADMIN"} ou une chaîne inconnue) échoue à la désérialisation Jackson, traduite
 * en {@code 400 Bad Request} par le comportement par défaut de Spring — validation stricte "dans
 * le DTO", sans logique de contrôle supplémentaire côté service.
 */
public enum AssignableRole {

    /** Droits d'administration du tenant (gestion des utilisateurs, activation de modules). */
    ROLE_ADMIN,

    /** Droits d'utilisation standard des modules activés par le tenant. */
    ROLE_USER
}
