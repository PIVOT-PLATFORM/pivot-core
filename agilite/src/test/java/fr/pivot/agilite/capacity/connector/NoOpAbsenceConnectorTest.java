package fr.pivot.agilite.capacity.connector;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Unit tests for {@link NoOpAbsenceConnector} (EN11.1 Wave 2 connectors). */
class NoOpAbsenceConnectorTest {

    private final NoOpAbsenceConnector connector = new NoOpAbsenceConnector();

    @Test
    void importAbsences_alwaysReturnsAnEmptyImmutableList() {
        List<ImportedAbsence> absences = connector.importAbsences(
                42L, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17));

        assertThat(absences).isEmpty();
        assertThatCode(() -> absences.add(new ImportedAbsence(
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6), 1.0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void importAbsences_nullTeamMemberRef_stillReturnsEmptyList() {
        List<ImportedAbsence> absences = connector.importAbsences(
                null, LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 17));

        assertThat(absences).isEmpty();
    }
}
