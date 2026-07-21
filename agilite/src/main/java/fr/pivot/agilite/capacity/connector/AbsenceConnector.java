package fr.pivot.agilite.capacity.connector;

import java.time.LocalDate;
import java.util.List;

/**
 * Imports a member's absences from an external HR system (SI-RH) for a given period (E11 —
 * capacity planning, EN11.1 Wave 2 connectors).
 *
 * <p>No implementation shipped in this wave makes a real network call — {@link
 * NoOpAbsenceConnector} is the only bound implementation, always returning an empty list. A real
 * SI-RH import (e.g. reading a bus event published by an HR module, or calling out to an external
 * HR API) is a follow-up US; this interface is the seam it will implement.
 */
public interface AbsenceConnector {

    /**
     * Imports the absences of one member over a period.
     *
     * @param teamMemberRef the linked {@code public.team_members.id}, or {@code null} for a
     *                      free-text (non-roster) member with no external identity to look up
     * @param startDate     the period's first calendar day (inclusive)
     * @param endDate       the period's last calendar day (inclusive)
     * @return the imported absences, an immutable list, never {@code null} — empty when the
     *         connector has nothing to import
     */
    List<ImportedAbsence> importAbsences(Long teamMemberRef, LocalDate startDate, LocalDate endDate);
}
