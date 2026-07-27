package fr.pivot.collaboratif.meetops.bus;

import java.time.Instant;
import java.util.List;

/**
 * In-process representation of the upstream {@code roadmap.event.window.updated} event
 * (US12.4.1) — same shape as {@link WindowCreatedEvent}, carried separately for clarity at the
 * consumption boundary even though the payload fields are identical. See {@link
 * WindowCreatedEvent}'s Javadoc for the producer-boundary rationale.
 *
 * @param tenantId          owning tenant's {@code public.tenants.id}
 * @param eventRef          upstream roadmap event correlation id — used to look up the existing
 *                          {@code Meeting}
 * @param projectRef        upstream roadmap project correlation id, or {@code null}
 * @param title             the event's updated {@code titre}
 * @param participants      updated participant identifiers (e-mails)
 * @param periodStart       updated start of the candidate booking period
 * @param periodEnd         updated end of the candidate booking period
 * @param durationMinutes   updated requested meeting duration, in minutes
 */
public record WindowUpdatedEvent(
        Long tenantId, String eventRef, String projectRef, String title, List<String> participants,
        Instant periodStart, Instant periodEnd, Integer durationMinutes) {
}
