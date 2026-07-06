package fr.pivot.auth.exception;

/**
 * Levée quand un administrateur tente de modifier son propre rôle via
 * {@code PATCH /api/admin/users/{userId}/role} (US06.1.3).
 *
 * <p>Un admin ne peut jamais se rétrograder (ni se re-promouvoir) lui-même par ce endpoint —
 * évite qu'un admin isolé se retire ses propres droits sans recours, ou qu'un compte compromis
 * s'auto-élève. Traduite en {@code 403 Forbidden} par {@code AdminUserController}.
 */
public class SelfRoleChangeForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour une tentative d'auto-modification de rôle.
     *
     * @param userId identifiant de l'administrateur appelant, cible de sa propre requête
     */
    public SelfRoleChangeForbiddenException(final Long userId) {
        super("Admin cannot change their own role: " + userId);
    }
}
