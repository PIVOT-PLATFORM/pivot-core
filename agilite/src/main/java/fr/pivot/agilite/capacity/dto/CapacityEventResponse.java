package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityEvent;
import fr.pivot.agilite.capacity.CapacityEventStatus;
import fr.pivot.agilite.capacity.CapacityEventType;
import fr.pivot.agilite.capacity.CapacityMaturityLevel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Response payload representing a capacity event visible to the caller (E11 — F11.1 events CRUD
 * + F11.3 hierarchy).
 *
 * @param id              unique identifier of the event
 * @param tenantId        {@code public.tenants.id} of the tenant that owns this event
 * @param teamId          {@code public.teams.id} this event belongs to
 * @param type            the kind of event
 * @param status          the event's current lifecycle status
 * @param name            the event's display name
 * @param startDate       the event's first calendar day (inclusive)
 * @param endDate         the event's last calendar day (inclusive)
 * @param parentId        the parent PI's identifier, or {@code null} for a root-level event
 * @param maturityLevel   the team maturity level override, or {@code null} if unset
 * @param focusFactor     the event-level default focus factor override, or {@code null} if unset
 * @param margeSecurite   the safety margin applied to the recommended engagement, or {@code null}
 *                        if unset
 * @param pointsPerDay    the story points per net person-day, or {@code null} if unset
 * @param committedPoints the committed story points, or {@code null} if not yet planned
 * @param completedPoints the completed story points, or {@code null} if not yet closed
 * @param workingDays     weekdays counted as working days, {@code 0} (Sunday) .. {@code 6}
 *                        (Saturday)
 * @param notes           free-form notes, or {@code null} if none
 * @param createdAt       timestamp when the event was created
 * @param updatedAt       timestamp of the last event update
 */
public record CapacityEventResponse(
        UUID id,
        Long tenantId,
        Long teamId,
        CapacityEventType type,
        CapacityEventStatus status,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        UUID parentId,
        CapacityMaturityLevel maturityLevel,
        Double focusFactor,
        Double margeSecurite,
        Double pointsPerDay,
        Double committedPoints,
        Double completedPoints,
        Integer[] workingDays,
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Factory method that creates a {@link CapacityEventResponse} from a {@link CapacityEvent}
     * entity.
     *
     * @param event the capacity event entity
     * @return a populated response record
     */
    public static CapacityEventResponse from(final CapacityEvent event) {
        return new CapacityEventResponse(
                event.getId(),
                event.getTenantId(),
                event.getTeamId(),
                event.getType(),
                event.getStatus(),
                event.getName(),
                event.getStartDate(),
                event.getEndDate(),
                event.getParentId(),
                event.getMaturityLevel(),
                event.getFocusFactor(),
                event.getMargeSecurite(),
                event.getPointsPerDay(),
                event.getCommittedPoints(),
                event.getCompletedPoints(),
                event.getWorkingDays(),
                event.getNotes(),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
