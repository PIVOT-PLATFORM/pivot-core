package fr.pivot.agilite.capacity.dto;

import java.time.LocalDate;

/**
 * One point of a burndown curve — ideal or actual (US11.4.2).
 *
 * @param date            the calendar date
 * @param pointsRemaining the points remaining on that date
 */
public record BurndownPointResponse(LocalDate date, double pointsRemaining) {
}
