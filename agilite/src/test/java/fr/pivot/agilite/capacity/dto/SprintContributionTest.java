package fr.pivot.agilite.capacity.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link SprintContribution} (E11 — capacity planning).
 */
class SprintContributionTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final EventCapacityResult sprintCapacity = new EventCapacityResult(
                10, List.of(), 9.0, 7.2, 10.8, 6.12, 1.1, 0.9);

        final SprintContribution contribution = new SprintContribution(sprintCapacity, true);

        assertThat(contribution.sprintCapacity()).isEqualTo(sprintCapacity);
        assertThat(contribution.ipSprint()).isTrue();
    }
}
