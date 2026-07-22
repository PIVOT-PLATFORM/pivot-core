package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityEventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Full response payload for a single capacity event (US11.1.1), including its parent (if any)
 * and children (if any) as lightweight {@link EventRef} summaries (US11.3.1).
 *
 * @param id              the event's id
 * @param type            the event's kind
 * @param name            the event's name
 * @param teamId          the owning team's {@code public.teams.id}
 * @param startDate       the event's start date
 * @param endDate         the event's end date
 * @param pointsPerDay    the points-per-day conversion factor, or {@code null}
 * @param committedPoints the committed points (US11.4.1), or {@code null}
 * @param completedPoints the completed points (US11.4.1), or {@code null}
 * @param createdBy       the creating user's {@code public.users.id}
 * @param createdAt       the creation timestamp
 * @param updatedAt       the last-update timestamp
 * @param parent             the parent PI Planning event's summary, or {@code null}
 * @param children           the event's direct children summaries, empty if none
 * @param isIpIteration      the IP-iteration flag (US11.5.1)
 * @param focusFactorPercent the event-level focus-factor override (US11.6.2), or {@code null}
 */
public record EventResponse(
        UUID id,
        CapacityEventType type,
        String name,
        Long teamId,
        LocalDate startDate,
        LocalDate endDate,
        Double pointsPerDay,
        Integer committedPoints,
        Integer completedPoints,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        EventRef parent,
        List<EventRef> children,
        boolean isIpIteration,
        Integer focusFactorPercent) {
}
