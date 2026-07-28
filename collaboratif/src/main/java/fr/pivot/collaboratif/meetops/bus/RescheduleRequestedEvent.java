package fr.pivot.collaboratif.meetops.bus;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a {@code window.updated}/{@code window.deleted} event is received for a meeting
 * that is already {@link fr.pivot.collaboratif.meeting.MeetingStatus#CONFIRMED} (US12.4.1
 * "cohérence window.updated/deleted") — a reprogramming request, never a silent cancellation.
 *
 * @param tenantId     owning tenant's {@code public.tenants.id}
 * @param meetingId    the confirmed meeting's id
 * @param eventRef     upstream roadmap event correlation id
 * @param requestedAt  when the reprogramming request was raised
 */
public record RescheduleRequestedEvent(Long tenantId, UUID meetingId, String eventRef, Instant requestedAt) {
}
