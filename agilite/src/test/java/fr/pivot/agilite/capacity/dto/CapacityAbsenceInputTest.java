package fr.pivot.agilite.capacity.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link CapacityAbsenceInput} (E11 — capacity planning).
 */
class CapacityAbsenceInputTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final LocalDate start = LocalDate.of(2026, 6, 3);
        final LocalDate end = LocalDate.of(2026, 6, 4);

        final CapacityAbsenceInput input = new CapacityAbsenceInput(start, end, 0.5);

        assertThat(input.startDate()).isEqualTo(start);
        assertThat(input.endDate()).isEqualTo(end);
        assertThat(input.fraction()).isEqualTo(0.5);
    }
}
