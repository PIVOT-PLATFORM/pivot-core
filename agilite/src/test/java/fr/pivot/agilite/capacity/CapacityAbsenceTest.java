package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour l'entité {@link CapacityAbsence} (E11 — capacity planning).
 */
class CapacityAbsenceTest {

    @Test
    void constructor_shouldSetAllFields() {
        final UUID eventMemberId = UUID.randomUUID();
        final Instant createdAt = Instant.now();

        final CapacityAbsence absence = new CapacityAbsence(
                eventMemberId,
                LocalDate.of(2026, 6, 3),
                LocalDate.of(2026, 6, 4),
                CapacityAbsence.FRACTION_FULL_DAY,
                CapacityAbsence.SOURCE_MANUAL,
                createdAt);

        assertThat(absence.getId()).isNull();
        assertThat(absence.getEventMemberId()).isEqualTo(eventMemberId);
        assertThat(absence.getStartDate()).isEqualTo(LocalDate.of(2026, 6, 3));
        assertThat(absence.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 4));
        assertThat(absence.getFraction()).isEqualTo(CapacityAbsence.FRACTION_FULL_DAY);
        assertThat(absence.getSource()).isEqualTo(CapacityAbsence.SOURCE_MANUAL);
        assertThat(absence.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    void constructor_shouldAcceptHalfDayFraction_andImportSource() {
        final CapacityAbsence absence = new CapacityAbsence(
                UUID.randomUUID(),
                LocalDate.of(2026, 6, 3),
                LocalDate.of(2026, 6, 3),
                CapacityAbsence.FRACTION_HALF_DAY,
                "IMPORT:teams",
                Instant.now());

        assertThat(absence.getFraction()).isEqualTo(CapacityAbsence.FRACTION_HALF_DAY);
        assertThat(absence.getSource()).isEqualTo("IMPORT:teams");
    }

    @Test
    void constants_shouldExposeExpectedValues() {
        assertThat(CapacityAbsence.FRACTION_FULL_DAY).isEqualTo(1.0);
        assertThat(CapacityAbsence.FRACTION_HALF_DAY).isEqualTo(0.5);
        assertThat(CapacityAbsence.SOURCE_MANUAL).isEqualTo("MANUAL");
    }
}
