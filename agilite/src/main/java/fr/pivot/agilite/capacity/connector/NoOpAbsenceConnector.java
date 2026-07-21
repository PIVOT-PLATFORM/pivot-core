package fr.pivot.agilite.capacity.connector;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Fallback {@link AbsenceConnector}: always returns an empty list, no I/O (E11 — capacity
 * planning, EN11.1 Wave 2 connectors).
 *
 * <p>A real SI-RH import connector (reading absences from an external HR system) is a follow-up —
 * until it exists, manually-entered {@code CapacityAbsence} rows remain the only absence source
 * feeding {@code CapacitySummaryService}.
 */
@Component
public class NoOpAbsenceConnector implements AbsenceConnector {

    @Override
    public List<ImportedAbsence> importAbsences(final Long teamMemberRef, final LocalDate startDate, final LocalDate endDate) {
        return List.of();
    }
}
