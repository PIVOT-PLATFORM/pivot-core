package fr.pivot.notification.exception;

/**
 * Levée quand {@code PATCH /api/notifications/{id}/read} cible une notification introuvable
 * pour l'utilisateur appelant.
 *
 * <p>Recouvre volontairement deux cas indistinguables pour l'appelant : la notification
 * n'existe pas du tout, ou existe mais appartient à un autre utilisateur (même tenant ou non).
 * Traduite en {@code 404 Not Found} (jamais {@code 403}) — voir CLAUDE.md « Règle transversale
 * sécurité — Isolation tenant » : ne jamais confirmer l'existence d'une ressource qui
 * n'appartient pas à l'appelant.
 */
public class NotificationNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Construit l'exception pour une notification introuvable pour l'appelant.
     *
     * @param notificationId identifiant de la notification recherchée
     */
    public NotificationNotFoundException(final Long notificationId) {
        super("Notification not found: " + notificationId);
    }
}
