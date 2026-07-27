package fr.pivot.collaboratif.meetops.booking.dto;

import fr.pivot.collaboratif.meeting.Meeting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response shape for a booking-flow meeting's state + proposed slots (US12.4.1) — returned by
 * {@code GET .../meetings/{id}}, {@code POST .../meetings/{id}/confirm} and {@code PATCH
 * .../meetings/{id}/slot}, and pushed on {@code /topic/collaboratif/meeting/{id}}.
 *
 * @param id                     meeting id
 * @param status                 lifecycle status ({@link Meeting#getStatus()}{@code .name()})
 * @param title                  meeting title
 * @param scheduledAt            scheduled date/time — the placeholder booking window start while
 *                               {@code PRE_RESERVED}, the confirmed slot start once {@code
 *                               CONFIRMED}
 * @param totalDurationMinutes   requested meeting duration in minutes
 * @param bookingWindowStart     start of the candidate period, or {@code null}
 * @param bookingWindowEnd       end of the candidate period, or {@code null}
 * @param eventRef               upstream roadmap event correlation id, or {@code null}
 * @param projectRef             upstream roadmap project correlation id, or {@code null}
 * @param rescheduleRequested    {@code true} when a {@code window.updated}/{@code window.deleted}
 *                               was received while this meeting was already {@code CONFIRMED}
 * @param proposedSlots          ranked candidates, rank ascending (possibly empty)
 */
public record MeetingBookingResponse(
        UUID id, String status, String title, Instant scheduledAt, Integer totalDurationMinutes,
        Instant bookingWindowStart, Instant bookingWindowEnd, String eventRef, String projectRef,
        boolean rescheduleRequested, List<ProposedSlotResponse> proposedSlots) {

    /**
     * Builds the response shape from a persisted {@link Meeting} plus its ranked slots.
     *
     * @param meeting the persisted entity
     * @param slots   the meeting's proposed slots, already rank-ordered
     * @return the response DTO
     */
    public static MeetingBookingResponse from(
            final Meeting meeting, final List<fr.pivot.collaboratif.meetops.booking.ProposedSlot> slots) {
        List<ProposedSlotResponse> slotResponses = slots.stream().map(ProposedSlotResponse::from).toList();
        return new MeetingBookingResponse(
                meeting.getId(), meeting.getStatus().name(), meeting.getTitle(), meeting.getScheduledAt(),
                meeting.getTotalDurationMinutes(), meeting.getBookingWindowStart(), meeting.getBookingWindowEnd(),
                meeting.getEventRef(), meeting.getProjectRef(), meeting.isRescheduleRequested(), slotResponses);
    }
}
