package fr.pivot.agilite.capacity.dto;

import java.time.LocalDate;

/**
 * Pure input value for one member absence, consumed by {@code
 * fr.pivot.agilite.capacity.calc.CapacityCalculator} (E11 — capacity planning).
 *
 * <p>Deliberately carries no {@code reason}/motif — same RGPD posture as {@code
 * fr.pivot.agilite.capacity.CapacityAbsence}, which this record is the calculator-facing
 * projection of.
 *
 * @param startDate the absence's first calendar day (inclusive)
 * @param endDate   the absence's last calendar day (inclusive)
 * @param fraction  {@code 1} (full day) or {@code 0.5} (half day)
 */
public record CapacityAbsenceInput(LocalDate startDate, LocalDate endDate, double fraction) {
}
