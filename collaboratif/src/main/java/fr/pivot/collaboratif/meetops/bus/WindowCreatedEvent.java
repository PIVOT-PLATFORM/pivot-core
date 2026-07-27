package fr.pivot.collaboratif.meetops.bus;

import java.time.Instant;
import java.util.List;

/**
 * In-process representation of the upstream {@code roadmap.event.window.created} event
 * (US12.4.1). EPIC-roadmap (US22.8.6, the real producer) is out of this sprint's scope — this
 * module only consumes the contract. Published via Spring's {@code ApplicationEventPublisher},
 * this codebase's existing convention for cross-module signals (see {@code
 * SessionKpiEventPublisher}'s Javadoc — "there is no dedicated PivotAdapter/message-broker bus in
 * this codebase today"); a genuine external bus wiring is the integration agent's follow-up once
 * EPIC-roadmap actually publishes.
 *
 * <p>{@code tenantId} is carried explicitly because, unlike a REST request, an inbound bus event
 * has no {@code CollaboratifRequestPrincipal} to derive it from — the (stubbed, test-only)
 * producer boundary is the sole source of this value for now.
 *
 * @param tenantId          owning tenant's {@code public.tenants.id}
 * @param eventRef          upstream roadmap event correlation id
 * @param projectRef        upstream roadmap project correlation id, or {@code null}
 * @param title             the event's {@code titre}
 * @param participants      participant identifiers (e-mails); the roadmap contract carries no
 *                          explicit organizer field — by convention the first entry is treated as
 *                          the organizer (see {@code BookingService}'s Javadoc)
 * @param periodStart       start of the candidate booking period
 * @param periodEnd         end of the candidate booking period
 * @param durationMinutes   requested meeting duration, in minutes
 */
public record WindowCreatedEvent(
        Long tenantId, String eventRef, String projectRef, String title, List<String> participants,
        Instant periodStart, Instant periodEnd, Integer durationMinutes) {
}
