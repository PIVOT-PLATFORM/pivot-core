package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityEventStatus;
import fr.pivot.agilite.capacity.CapacityEventType;
import fr.pivot.agilite.capacity.CapacityMaturityLevel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request body for creating or updating a {@code CapacityEvent} (E11 — F11.1 events CRUD +
 * F11.3 hierarchy).
 *
 * <p>Used both by {@code POST /events} (create) and {@code PUT /events/{id}} (update): on
 * update, {@code teamId} is ignored by the service — an event's owning team never changes after
 * creation, only its own body fields do. Domain rules not expressible via bean validation (date
 * range, hierarchy depth, {@code focusFactor}/{@code margeSecurite} range) are enforced in {@code
 * CapacityEventService}, which throws {@code CapacityValidationException} with a stable {@code
 * code}.
 *
 * @param teamId          the owning team's {@code public.teams.id} (create only, ignored on
 *                         update)
 * @param type            the kind of event
 * @param name            the event's display name, 1-200 characters
 * @param startDate       the event's first calendar day (inclusive)
 * @param endDate         the event's last calendar day (inclusive), must not be before {@code
 *                        startDate}
 * @param parentId        the parent PI's identifier, or {@code null} for a root-level event
 * @param maturityLevel   the team maturity level override, or {@code null} to leave unset
 * @param focusFactor     the event-level default focus factor override, in {@code [0, 1]}, or
 *                        {@code null}
 * @param margeSecurite   the safety margin applied to the recommended engagement, in {@code
 *                        [0, 1]}, or {@code null}
 * @param pointsPerDay    the story points per net person-day, or {@code null}
 * @param committedPoints the committed story points, or {@code null}
 * @param workingDays     weekdays counted as working days, {@code 0} (Sunday) .. {@code 6}
 *                        (Saturday); defaults to Monday-Friday ({@code [1,2,3,4,5]}) when omitted
 * @param notes           free-form notes, or {@code null}
 * @param status          the event's lifecycle status; defaults to {@link
 *                         CapacityEventStatus#PLANNING} on create when omitted
 */
public record CapacityEventRequest(
        Long teamId,
        @NotNull(message = "INVALID_TYPE")
        CapacityEventType type,
        @NotBlank(message = "INVALID_NAME")
        @Size(min = 1, max = 200, message = "INVALID_NAME")
        String name,
        @NotNull(message = "INVALID_START_DATE")
        LocalDate startDate,
        @NotNull(message = "INVALID_END_DATE")
        LocalDate endDate,
        UUID parentId,
        CapacityMaturityLevel maturityLevel,
        Double focusFactor,
        Double margeSecurite,
        Double pointsPerDay,
        Double committedPoints,
        Integer[] workingDays,
        String notes,
        CapacityEventStatus status) {
}
