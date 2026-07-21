package fr.pivot.agilite.capacity.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Response for a team's velocity history and rolling forecast ({@code GET
 * /capacity/events/{id}/history}, F11.4).
 *
 * @param history  the team's recent sprints (oldest first), each with its velocity snapshot if
 *                  the sprint has been closed out yet ({@code pointsEngages}/{@code
 *                  pointsLivres} {@code null} otherwise)
 * @param forecast the rolling-window forecast computed by {@code
 *                  CapacityCalculator#forecastVelocity}, or {@code null} if none of {@code
 *                  history}'s sprints have a velocity snapshot yet
 */
public record CapacityHistoryResponse(List<HistoryPoint> history, VelocityForecast forecast) {

    /**
     * One sprint's contribution to the velocity history.
     *
     * @param sprintEventId the sprint's identifier
     * @param name           the sprint's display name
     * @param startDate      the sprint's first calendar day
     * @param pointsEngages  the sprint's committed points, or {@code null} if not closed out yet
     * @param pointsLivres   the sprint's completed points, or {@code null} if not closed out yet
     */
    public record HistoryPoint(
            UUID sprintEventId, String name, LocalDate startDate, Double pointsEngages, Double pointsLivres) {
    }
}
