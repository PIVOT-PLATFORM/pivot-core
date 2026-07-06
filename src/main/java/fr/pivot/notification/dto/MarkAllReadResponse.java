package fr.pivot.notification.dto;

/**
 * Corps de réponse de {@code PATCH /api/notifications/read-all} (EN-NOTIF AC).
 *
 * @param updatedCount nombre de notifications effectivement passées de non-lue à lue par cet
 *                      appel — {@code 0} si l'utilisateur n'avait déjà aucune notification non
 *                      lue (appel idempotent, jamais d'erreur)
 */
public record MarkAllReadResponse(int updatedCount) {
}
