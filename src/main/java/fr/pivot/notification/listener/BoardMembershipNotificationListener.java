package fr.pivot.notification.listener;

import fr.pivot.collaboratif.whiteboard.member.event.BoardMembershipNotificationRequestedEvent;
import fr.pivot.notification.service.NotificationPayload;
import fr.pivot.notification.service.NotificationService;
import fr.pivot.notification.service.NotificationType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges {@link BoardMembershipNotificationRequestedEvent} (US08.2.5, published by {@code
 * fr.pivot.collaboratif.whiteboard.member.BoardMemberService}) to the shared {@link
 * NotificationService} — see that event's Javadoc for why this indirection exists: {@code
 * collaboratif} cannot depend on this package directly (this {@code app} module depends on {@code
 * collaboratif}, not the reverse — a direct call would be a circular Maven dependency).
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} — only fires once the membership
 * mutation the event describes has actually committed, mirroring {@link NotificationPushListener}
 * ; and {@code NotificationService#create} runs its own separate transaction, publishing its own
 * {@link fr.pivot.notification.event.NotificationCreatedEvent} for the STOMP push, unaffected by
 * whatever happens to the caller's original transaction after this listener returns.
 */
@Component
public class BoardMembershipNotificationListener {

    private final NotificationService notificationService;

    /**
     * Creates the listener with its required collaborator.
     *
     * @param notificationService the shared platform in-app notification emitter (EN-NOTIF)
     */
    public BoardMembershipNotificationListener(final NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Translates the event's {@code kind} into the matching {@link NotificationType} and emits it.
     *
     * @param event the event published by {@code BoardMemberService}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBoardMembershipNotificationRequested(
            final BoardMembershipNotificationRequestedEvent event) {
        NotificationType type = switch (event.kind()) {
            case SHARED -> NotificationType.BOARD_SHARED;
            case ROLE_CHANGED -> NotificationType.BOARD_ROLE_CHANGED;
            case ACCESS_REVOKED -> NotificationType.BOARD_ACCESS_REVOKED;
        };
        NotificationPayload payload = event.role() != null
                ? NotificationPayload.of(event.boardTitle(), event.role())
                : NotificationPayload.of(event.boardTitle());
        notificationService.create(event.recipientUserId(), type, payload);
    }
}
