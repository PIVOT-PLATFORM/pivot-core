package fr.pivot.collaboratif.meetops.bus;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code meetops.booking.confirmed} signal (US12.4.1) — published via {@code
 * ApplicationEventPublisher} once an organizer confirms a slot, mirroring {@code
 * SessionKpiUpdatedEvent}'s in-process bus convention (see {@link WindowCreatedEvent}'s Javadoc).
 * A future integration agent bridges this onto the real cross-module bus (ActiveMQ relay, {@code
 * /topic/collaboratif.booking.confirmed}) once a genuine downstream consumer needs it — this
 * sprint's scope only requires the event to be observably published at this boundary.
 *
 * @param tenantId    owning tenant's {@code public.tenants.id}
 * @param meetingId   the confirmed meeting's id
 * @param eventRef    upstream roadmap event correlation id
 * @param slotStart   the confirmed slot's start
 * @param slotEnd     the confirmed slot's end
 */
public record BookingConfirmedEvent(Long tenantId, UUID meetingId, String eventRef, Instant slotStart, Instant slotEnd) {
}
