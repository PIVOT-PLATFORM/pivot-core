package fr.pivot.agilite.capacity.cadence;

import java.time.LocalDate;

/**
 * A single computed sprint slot within a PI's cadence layout (F11.5), produced by {@link
 * CadencePlanner#plan(LocalDate, LocalDate, CadenceRequest)} — pure data, not yet persisted.
 *
 * @param name      the generated display name (e.g. {@code "Sprint 1"}, or {@code "Sprint 5
 *                  (IP)"} for the trailing IP sprint)
 * @param startDate the sprint's first calendar day (inclusive)
 * @param endDate   the sprint's last calendar day (inclusive)
 * @param ipSprint  {@code true} if this slot is the trailing SAFe Innovation &amp; Planning
 *                  sprint
 */
public record SprintPlan(
        String name,
        LocalDate startDate,
        LocalDate endDate,
        boolean ipSprint) {
}
