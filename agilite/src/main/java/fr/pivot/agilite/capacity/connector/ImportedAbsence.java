package fr.pivot.agilite.capacity.connector;

import java.time.LocalDate;

/**
 * One absence imported from an external HR/SI-RH system by an {@link AbsenceConnector} (E11 —
 * capacity planning, EN11.1 Wave 2 connectors).
 *
 * <p>This is the connector-facing projection — deliberately carries no {@code reason}/motif, same
 * RGPD posture as {@code fr.pivot.agilite.capacity.CapacityAbsence}. Mapping an {@code
 * ImportedAbsence} onto a persisted {@code CapacityAbsence} (with {@code source =
 * "IMPORT:&lt;connector&gt;"}) is left to the future service layer that wires a real connector in.
 *
 * @param startDate the absence's first calendar day (inclusive)
 * @param endDate   the absence's last calendar day (inclusive)
 * @param fraction  {@code 1} (full day) or {@code 0.5} (half day)
 */
public record ImportedAbsence(LocalDate startDate, LocalDate endDate, double fraction) {
}
