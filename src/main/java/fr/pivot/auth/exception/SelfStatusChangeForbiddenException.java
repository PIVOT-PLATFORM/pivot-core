package fr.pivot.auth.exception;

/**
 * Levée quand un administrateur tente de désactiver son propre compte via
 * {@code PATCH /api/admin/users/{userId}/status} (US06.1.4).
 *
 * <p>Asymétrique par construction : seule une tentative de désactivation
 * ({@code status: "INACTIVE"}) sur son propre {@code userId} est rejetée — un admin peut
 * toujours cibler son propre compte avec {@code status: "ACTIVE"} (US06.1.5), ce qui n'a de
 * toute façon aucun effet réel puisqu'un compte désactivé ne peut plus s'authentifier pour
 * émettre cette requête. Évite qu'un admin isolé se coupe lui-même l'accès sans recours.
 * Traduite en {@code 403 Forbidden} par {@code AdminUserController}.
 */
public class SelfStatusChangeForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour une tentative d'auto-désactivation.
     *
     * @param userId identifiant de l'administrateur appelant, cible de sa propre requête
     */
    public SelfStatusChangeForbiddenException(final Long userId) {
        super("Admin cannot deactivate their own account: " + userId);
    }
}
