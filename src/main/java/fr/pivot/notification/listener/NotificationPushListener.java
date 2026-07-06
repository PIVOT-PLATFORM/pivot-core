package fr.pivot.notification.listener;

import fr.pivot.notification.event.NotificationCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pousse chaque notification créée sur le canal STOMP {@code /user/{userId}/queue/notifications}
 * (EN-NOTIF AC).
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} — ne s'exécute qu'une fois la
 * transaction de {@code NotificationService#create} effectivement validée : aucun push n'est
 * jamais envoyé pour une notification dont l'écriture serait finalement annulée (rollback).
 *
 * <p>Un échec de livraison (utilisateur non connecté au WebSocket, canal indisponible…) est
 * capturé et journalisé — jamais propagé : la notification reste consultable via {@code GET
 * /api/notifications} et {@code GET /api/notifications/unread-count} (polling 30 s, fallback
 * documenté par l'AC EN-NOTIF) même si le push temps réel échoue.
 */
@Component
public class NotificationPushListener {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationPushListener.class);

    /** Suffixe de destination STOMP relatif à l'utilisateur — voir {@code /user/{userId}/...}. */
    private static final String DESTINATION = "/queue/notifications";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Construit le listener avec son collaborateur.
     *
     * @param messagingTemplate template STOMP configuré par {@code NotificationWebSocketConfig}
     */
    public NotificationPushListener(final SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Pousse la notification créée vers son destinataire, une fois la transaction validée.
     *
     * @param event événement publié par {@code NotificationService#create}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(final NotificationCreatedEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(event.userId().toString(), DESTINATION, event.notification());
        } catch (final MessagingException e) {
            LOG.warn("event=NOTIFICATION_PUSH_FAILED userId={} reason={}", event.userId(), e.getMessage());
        }
    }
}
