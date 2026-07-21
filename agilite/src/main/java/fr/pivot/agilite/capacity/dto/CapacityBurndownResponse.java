package fr.pivot.agilite.capacity.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Response for a sprint's burndown chart ({@code GET /capacity/events/{id}/burndown}, F11.4):
 * the real, recorded burndown line plus a derived ideal line.
 *
 * @param real  the recorded {@code capacity_burndown_point} readings, oldest first
 * @param ideal a linear reference line, one point per calendar day of the event window, from the
 *              event's committed points (day 1) down to {@code 0} (last day) — {@code 0}
 *              throughout when the event has no {@code committedPoints} set
 */
public record CapacityBurndownResponse(List<BurndownPoint> real, List<BurndownPoint> ideal) {

    /**
     * One (date, remaining points) reading of a burndown line.
     *
     * @param date           the calendar day
     * @param pointsRestants the remaining points as of {@code date}
     */
    public record BurndownPoint(LocalDate date, double pointsRestants) {
    }
}
