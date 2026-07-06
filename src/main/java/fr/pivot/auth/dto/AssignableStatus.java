package fr.pivot.auth.dto;

/**
 * Statuts qu'un administrateur tenant peut assigner à un utilisateur via
 * {@code PATCH /api/admin/users/{userId}/status} (US06.1.4 « Admin désactive un compte
 * utilisateur » / US06.1.5 « Admin réactive un compte utilisateur »).
 *
 * <p>Volontairement fermé à {@link #ACTIVE} et {@link #INACTIVE} — {@link UserStatus#BLOCKED}
 * (statut dérivé de {@code is_blocked}, piloté par un mécanisme distinct, hors périmètre de ces
 * deux US) est exclu par construction. Utilisé comme type de champ direct dans
 * {@link UpdateUserStatusRequest} : toute valeur JSON hors de cette énumération (y compris
 * {@code "BLOCKED"} ou une chaîne inconnue) échoue à la désérialisation Jackson, traduite en
 * {@code 400 Bad Request} par le comportement par défaut de Spring — validation stricte "dans le
 * DTO", sans logique de contrôle supplémentaire côté service (symétrique de
 * {@link AssignableRole}).
 */
public enum AssignableStatus {

    /** Réactive le compte (US06.1.5) — idempotent si déjà {@code ACTIVE}. */
    ACTIVE,

    /** Désactive le compte (US06.1.4) — révoque immédiatement toutes les sessions actives. */
    INACTIVE
}
