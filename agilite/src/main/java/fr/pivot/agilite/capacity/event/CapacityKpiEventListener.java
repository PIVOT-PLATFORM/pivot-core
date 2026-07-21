package fr.pivot.agilite.capacity.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Bridges {@link CapacityUpdatedEvent} to {@link KpiUpdatedEvent} (EN11.2) — this module's own
 * "hook without editing existing files" the ticket's brief calls for: rather than requiring every
 * future capacity-mutating service to also publish a {@code kpi.updated} signal itself, this
 * listener derives one automatically from whatever already publishes {@link CapacityUpdatedEvent}
 * via {@link CapacityUpdatedEventPublisher}.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} — only fires once the capacity
 * mutation the event describes has actually committed, the same pattern already used by {@code
 * fr.pivot.notification.listener.NotificationPushListener} and {@code
 * fr.pivot.notification.listener.BoardMembershipNotificationListener} in this repo. By the time
 * this listener runs, the original transaction has already committed, so — again mirroring {@code
 * BoardMembershipNotificationListener}'s own note about {@code NotificationService#create} running
 * its own separate transaction — the {@link KpiUpdatedEvent} republished here is a plain,
 * non-transactional {@link ApplicationEventPublisher#publishEvent} call: a future consumer of
 * {@code KpiUpdatedEvent} should use a plain {@code @EventListener}, not {@code
 * @TransactionalEventListener}, since no transaction is active at this point to key a phase off
 * of.
 */
@Component
public class CapacityKpiEventListener {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates the listener with its required collaborator.
     *
     * @param eventPublisher Spring's in-process application event bus, used to republish the
     *                       derived {@link KpiUpdatedEvent}
     */
    public CapacityKpiEventListener(final ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Derives and publishes the {@link KpiUpdatedEvent} for the team the committed capacity
     * mutation belongs to.
     *
     * @param event the committed {@link CapacityUpdatedEvent}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCapacityUpdated(final CapacityUpdatedEvent event) {
        eventPublisher.publishEvent(new KpiUpdatedEvent(event.tenantId(), event.teamRef(), Instant.now()));
    }
}
