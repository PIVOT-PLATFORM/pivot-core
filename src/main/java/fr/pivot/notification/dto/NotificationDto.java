package fr.pivot.notification.dto;

import fr.pivot.notification.entity.Notification;
import fr.pivot.notification.service.NotificationType;

import java.time.Instant;

/**
 * Représentation API-safe d'une {@link Notification} — jamais l'entité JPA directement
 * (fuite de {@code user}/{@code tenant} chargés en lazy, IDOR potentiel).
 *
 * @param id        identifiant technique de la notification
 * @param type      type de notification (voir {@link NotificationType})
 * @param title     titre déjà résolu dans la locale du destinataire au moment de la création
 * @param body      corps déjà résolu dans la locale du destinataire au moment de la création
 * @param readAt    horodatage de lecture, {@code null} si non lue
 * @param createdAt horodatage de création
 */
public record NotificationDto(
        Long id,
        NotificationType type,
        String title,
        String body,
        Instant readAt,
        Instant createdAt) {

    /**
     * Convertit une entité {@link Notification} en {@link NotificationDto}.
     *
     * @param notification entité source
     * @return DTO sûr pour sérialisation API
     */
    public static NotificationDto from(final Notification notification) {
        return new NotificationDto(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
