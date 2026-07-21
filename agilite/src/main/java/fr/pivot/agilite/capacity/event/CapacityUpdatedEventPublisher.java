package fr.pivot.agilite.capacity.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link CapacityUpdatedEvent} for a capacity mutation, exactly the same one-line idiom
 * already used across this repo's cross-module event producers — e.g. {@code
 * fr.pivot.collaboratif.whiteboard.member.BoardMemberService#inviteOrReinvite}, which calls {@code
 * eventPublisher.publishEvent(new BoardMembershipNotificationRequestedEvent(...))} inline from
 * inside its own {@code @Transactional} method, or {@code
 * fr.pivot.collaboratif.whiteboard.canvas.CanvasActionService}'s equivalent call for {@code
 * CardContentEnrichmentRequestedEvent}. {@link org.springframework.context.ApplicationEventPublisher#publishEvent}
 * itself is synchronous and transaction-agnostic — it is the {@code @TransactionalEventListener(phase
 * = AFTER_COMMIT)} on the <em>consumer</em> side (see {@link CapacityKpiEventListener}) that defers
 * actual handling until the publishing transaction commits.
 *
 * <p><strong>Wired, but not yet called.</strong> This ticket (EN11.2) is new-files-only — it must
 * not edit {@code CapacityEventService}/{@code CapacityMemberService}/{@code
 * CapacityVelocityService}, the services that actually mutate capacity data and would be this
 * publisher's real callers. This {@code @Component} is therefore ready to inject (Spring will
 * autowire it into any of those services once they add a constructor parameter for it) but is not
 * invoked by them yet — a follow-up ticket adds the one-line {@code
 * capacityUpdatedEventPublisher.publish(new CapacityUpdatedEvent(...))} call at the end of each
 * mutating method (mirroring {@code BoardMemberService}'s placement — right after the {@code
 * save(...)} that made the mutation durable, still inside the same {@code @Transactional}
 * boundary, so a rollback also cancels the event).
 */
@Component
public class CapacityUpdatedEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates the publisher with its required collaborator.
     *
     * @param eventPublisher Spring's in-process application event bus
     */
    public CapacityUpdatedEventPublisher(final ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes the event. Intended to be called from inside a {@code @Transactional} capacity
     * mutation method, right after the mutation is persisted — see class Javadoc.
     *
     * @param event the capacity update to publish
     */
    public void publish(final CapacityUpdatedEvent event) {
        eventPublisher.publishEvent(event);
    }
}
