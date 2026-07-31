package fr.pivot.collaboratif.meetops.bus;

/**
 * In-process representation of the upstream {@code roadmap.event.window.deleted} event
 * (US12.4.1). See {@link WindowCreatedEvent}'s Javadoc for the producer-boundary rationale.
 *
 * @param tenantId owning tenant's {@code public.tenants.id}
 * @param eventRef upstream roadmap event correlation id — used to look up the existing
 *                 {@code Meeting}
 */
public record WindowDeletedEvent(Long tenantId, String eventRef) {
}
