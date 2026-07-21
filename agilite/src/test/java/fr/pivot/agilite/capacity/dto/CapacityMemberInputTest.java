package fr.pivot.agilite.capacity.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link CapacityMemberInput} (E11 — capacity planning).
 */
class CapacityMemberInputTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final List<CapacityAbsenceInput> absences = List.of(
                new CapacityAbsenceInput(LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 3), 1.0));

        final CapacityMemberInput input = new CapacityMemberInput("m-1", 1.0, 0.8, "Dev", false, 0, absences);

        assertThat(input.id()).isEqualTo("m-1");
        assertThat(input.quotite()).isEqualTo(1.0);
        assertThat(input.focusFactor()).isEqualTo(0.8);
        assertThat(input.role()).isEqualTo("Dev");
        assertThat(input.excluded()).isFalse();
        assertThat(input.position()).isZero();
        assertThat(input.absences()).isEqualTo(absences);
    }
}
