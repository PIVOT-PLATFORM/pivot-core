package fr.pivot.notification.service;

/**
 * Types de notification in-app connus de la plateforme (EN-NOTIF).
 *
 * <p>Référentiel fermé — persisté tel quel (colonne {@code notifications.type}, contrainte
 * {@code chk_notifications_type}) — voir {@code V1__schema_init.sql}. Chaque constante porte les
 * deux clés {@code messages.properties} (titre/corps) résolues par
 * {@link NotificationService#create} via {@link org.springframework.context.MessageSource}, dans
 * la locale du destinataire ({@code users.locale}).
 *
 * <p><strong>Producteurs (EN-NOTIF AC « Producteurs définis »)</strong> :
 * <ul>
 *   <li>{@link #ROLE_CHANGED} — US06.1.3, câblé dans {@code AdminUserService#updateRole}</li>
 *   <li>{@link #ACCOUNT_DEACTIVATED} — US06.1.4, câblé dans
 *       {@code AdminUserService#updateStatus} (branche {@code INACTIVE} uniquement)</li>
 *   <li>{@link #SENSITIVE_ACTION} — US01.5.1, <strong>pas encore câblé</strong> : le producteur
 *       (core PR #154, branche {@code feat/us01-5-1-email-action-sensible}) n'est pas fusionné
 *       sur {@code main} et ne publie aucun événement consommable aujourd'hui — voir
 *       {@code fr.pivot.notification.listener} (package-info.java) pour le point d'intégration
 *       documenté</li>
 *   <li>{@link #UNKNOWN_DEVICE} — US01.4.3a, <strong>pas encore câblé</strong> : même situation
 *       (core PR #151, branche {@code feat/us01-4-3a-alerte-connexion-suspecte})</li>
 *   <li>{@link #BOARD_SHARED}, {@link #BOARD_ROLE_CHANGED}, {@link #BOARD_ACCESS_REVOKED} —
 *       US08.2.5, câblés dans {@code fr.pivot.collaboratif.whiteboard.member.BoardMemberService}
 *       — premier producteur porté par un module métier (collaboratif) plutôt que par le shell</li>
 * </ul>
 */
public enum NotificationType {

    /** US06.1.3 — le rôle de l'utilisateur a été modifié par un administrateur de son tenant. */
    ROLE_CHANGED("notification.role-changed.title", "notification.role-changed.body"),

    /** US06.1.4 — le compte de l'utilisateur a été désactivé par un administrateur. */
    ACCOUNT_DEACTIVATED("notification.account-deactivated.title", "notification.account-deactivated.body"),

    /**
     * US01.5.1 — action sensible sur le compte (mot de passe modifié, email modifié, suppression
     * de compte demandée, sessions révoquées). Défini par anticipation — voir JavaDoc de classe.
     */
    SENSITIVE_ACTION("notification.sensitive-action.title", "notification.sensitive-action.body"),

    /**
     * US01.4.3a — connexion détectée depuis un appareil inconnu. Défini par anticipation — voir
     * JavaDoc de classe.
     */
    UNKNOWN_DEVICE("notification.unknown-device.title", "notification.unknown-device.body"),

    /** US08.2.5 — un tableau whiteboard a été partagé avec l'utilisateur pour la première fois. */
    BOARD_SHARED("notification.board-shared.title", "notification.board-shared.body"),

    /** US08.2.5 — le rôle de l'utilisateur sur un tableau whiteboard a été modifié. */
    BOARD_ROLE_CHANGED("notification.board-role-changed.title", "notification.board-role-changed.body"),

    /** US08.2.5 — l'accès de l'utilisateur à un tableau whiteboard a été révoqué. */
    BOARD_ACCESS_REVOKED("notification.board-access-revoked.title", "notification.board-access-revoked.body");

    private final String titleKey;
    private final String bodyKey;

    NotificationType(final String titleKey, final String bodyKey) {
        this.titleKey = titleKey;
        this.bodyKey = bodyKey;
    }

    /**
     * Clé {@code messages.properties} du titre de ce type de notification.
     *
     * @return clé de message, résolue via {@link org.springframework.context.MessageSource}
     */
    public String titleKey() {
        return titleKey;
    }

    /**
     * Clé {@code messages.properties} du corps de ce type de notification.
     *
     * @return clé de message, résolue via {@link org.springframework.context.MessageSource}
     */
    public String bodyKey() {
        return bodyKey;
    }
}
