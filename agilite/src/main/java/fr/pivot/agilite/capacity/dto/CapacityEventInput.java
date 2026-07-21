package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityMaturityLevel;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure input value for one capacity event, consumed by {@code
 * fr.pivot.agilite.capacity.calc.CapacityCalculator} (E11 — capacity planning).
 *
 * <p>Holidays are injected here rather than looked up by the calculator itself — new vs. the
 * PouetPouet POC, and deliberately decoupled from any database/module (the calculator stays pure,
 * no I/O): the (future) service layer resolves the applicable holiday calendar and passes the
 * resulting date set in.
 *
 * @param startDate         the event's first calendar day (inclusive)
 * @param endDate           the event's last calendar day (inclusive)
 * @param workingDays       weekdays counted as working days, {@code 0} (Sunday) .. {@code 6}
 *                          (Saturday) — see {@code CapacityCalculator#weekdayIndex}
 * @param holidays          calendar days excluded from working-day counts regardless of {@code
 *                          workingDays}, injected by the caller
 * @param focusFactor       the event-level default focus factor, in {@code [0, 1]}, used when a
 *                          member has neither its own override nor a matching {@code
 *                          roleFocusFactors} entry — {@code null} falls further back to {@link
 *                          fr.pivot.agilite.capacity.calc.CapacityCalculator#maturityProfile}
 * @param roleFocusFactors  per-role focus factor overrides, in {@code [0, 1]} — consulted before
 *                          {@code focusFactor} but after a member's own override; {@code null} or
 *                          missing entries are simply skipped
 * @param margeSecurite     the safety margin applied to the recommended engagement, in {@code [0,
 *                          1]} — {@code null} falls back to {@link
 *                          fr.pivot.agilite.capacity.calc.CapacityCalculator#maturityProfile}'s
 *                          default margin
 * @param pointsPerDay      story points per net person-day, or {@code null} if points are not
 *                          tracked for this event (points/engagement-in-points fields of the
 *                          result stay {@code null} in that case)
 * @param maturityLevel     the team maturity level, or {@code null} to use the default profile
 *                          (see {@link fr.pivot.agilite.capacity.calc.CapacityCalculator})
 * @param committedPoints   the committed story points, or {@code null} if not yet planned
 * @param completedPoints   the completed story points, or {@code null} if not yet closed
 * @param members           the event's members
 */
public record CapacityEventInput(
        LocalDate startDate,
        LocalDate endDate,
        Set<Integer> workingDays,
        Set<LocalDate> holidays,
        Double focusFactor,
        Map<String, Double> roleFocusFactors,
        Double margeSecurite,
        Double pointsPerDay,
        CapacityMaturityLevel maturityLevel,
        Double committedPoints,
        Double completedPoints,
        List<CapacityMemberInput> members) {
}
