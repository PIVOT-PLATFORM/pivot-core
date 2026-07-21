package fr.pivot.agilite.capacity.dto;

import fr.pivot.agilite.capacity.CapacityMaturityLevel;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link CapacityEventInput} (E11 — capacity planning).
 */
class CapacityEventInputTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final LocalDate start = LocalDate.of(2026, 6, 1);
        final LocalDate end = LocalDate.of(2026, 6, 12);
        final Set<Integer> workingDays = Set.of(1, 2, 3, 4, 5);
        final Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 6, 8));
        final Map<String, Double> roleFocusFactors = Map.of("Dev", 0.8);
        final List<CapacityMemberInput> members = List.of();

        final CapacityEventInput input = new CapacityEventInput(
                start,
                end,
                workingDays,
                holidays,
                0.8,
                roleFocusFactors,
                0.15,
                1.5,
                CapacityMaturityLevel.PERFORMING,
                50.0,
                45.0,
                members);

        assertThat(input.startDate()).isEqualTo(start);
        assertThat(input.endDate()).isEqualTo(end);
        assertThat(input.workingDays()).isEqualTo(workingDays);
        assertThat(input.holidays()).isEqualTo(holidays);
        assertThat(input.focusFactor()).isEqualTo(0.8);
        assertThat(input.roleFocusFactors()).isEqualTo(roleFocusFactors);
        assertThat(input.margeSecurite()).isEqualTo(0.15);
        assertThat(input.pointsPerDay()).isEqualTo(1.5);
        assertThat(input.maturityLevel()).isEqualTo(CapacityMaturityLevel.PERFORMING);
        assertThat(input.committedPoints()).isEqualTo(50.0);
        assertThat(input.completedPoints()).isEqualTo(45.0);
        assertThat(input.members()).isEqualTo(members);
    }
}
