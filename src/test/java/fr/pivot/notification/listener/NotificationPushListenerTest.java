package fr.pivot.notification.listener;

import fr.pivot.notification.dto.NotificationDto;
import fr.pivot.notification.event.NotificationCreatedEvent;
import fr.pivot.notification.service.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Tests unitaires pour {@link NotificationPushListener} (EN-NOTIF).
 *
 * <p>Le déclenchement effectif en {@code AFTER_COMMIT} (mécanique transactionnelle Spring) n'est
 * pas exerçable ici — couvert par les tests d'intégration Testcontainers. Ce test vérifie
 * uniquement la traduction événement → appel STOMP et la tolérance aux échecs de livraison.
 */
@ExtendWith(MockitoExtension.class)
class NotificationPushListenerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private NotificationPushListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationPushListener(messagingTemplate);
    }

    @Test
    void onNotificationCreated_pushesToUserQueueNotifications() {
        final NotificationDto dto = new NotificationDto(
                1L, NotificationType.ROLE_CHANGED, "Titre", "Corps", null, Instant.now());
        final NotificationCreatedEvent event = new NotificationCreatedEvent(7L, dto);

        listener.onNotificationCreated(event);

        verify(messagingTemplate).convertAndSendToUser("7", "/queue/notifications", dto);
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void onNotificationCreated_neverThrows_whenPushDeliveryFails() {
        final NotificationDto dto = new NotificationDto(
                1L, NotificationType.ACCOUNT_DEACTIVATED, "Titre", "Corps", null, Instant.now());
        final NotificationCreatedEvent event = new NotificationCreatedEvent(9L, dto);
        doThrow(new MessagingException("boom"))
                .when(messagingTemplate).convertAndSendToUser("9", "/queue/notifications", dto);

        // GET /api/notifications/unread-count (polling) reste le filet de sécurité — un échec de
        // push ne doit jamais remonter à l'appelant (voir NotificationPushListener JavaDoc).
        listener.onNotificationCreated(event);
    }
}
