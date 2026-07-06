package fr.pivot.notification.event;

import fr.pivot.notification.dto.NotificationDto;

/**
 * Événement interne publié après la persistance réussie d'une notification
 * ({@code NotificationService#create}), consommé par
 * {@code fr.pivot.notification.listener.NotificationPushListener} pour le push STOMP
 * ({@code /user/{userId}/queue/notifications} — EN-NOTIF AC).
 *
 * <p>Ne porte que des types immuables/primitifs — jamais l'entité JPA {@code Notification} ni
 * {@code User} — même principe que {@code fr.pivot.core.modules.event.ModuleLifecycleEvent} :
 * évite toute fuite d'entité potentiellement détachée par le moment où un
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} s'exécute (session Hibernate déjà
 * fermée).
 *
 * @param userId       identifiant du destinataire — utilisé comme nom de principal STOMP par
 *                      {@code SimpMessagingTemplate#convertAndSendToUser}
 * @param notification représentation API-safe déjà résolue (titre/corps déjà rendus)
 */
public record NotificationCreatedEvent(Long userId, NotificationDto notification) {
}
