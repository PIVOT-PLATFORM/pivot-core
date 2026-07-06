package fr.pivot.notification.dto;

/**
 * Corps de réponse de {@code GET /api/notifications/unread-count} (EN-NOTIF AC).
 *
 * @param count nombre de notifications non lues de l'utilisateur authentifié, dans son tenant
 */
public record UnreadCountResponse(long count) {
}
