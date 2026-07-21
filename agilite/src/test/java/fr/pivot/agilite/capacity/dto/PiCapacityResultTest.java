package fr.pivot.agilite.capacity.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link PiCapacityResult} (E11 — capacity planning).
 */
class PiCapacityResultTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final PiCapacityResult result = new PiCapacityResult(45.0, 36.0, 54.0, 4, 1);

        assertThat(result.totalJoursHommeNets()).isEqualTo(45.0);
        assertThat(result.totalCapaciteNette()).isEqualTo(36.0);
        assertThat(result.totalPoints()).isEqualTo(54.0);
        assertThat(result.includedSprintCount()).isEqualTo(4);
        assertThat(result.excludedIpSprintCount()).isEqualTo(1);
    }

    @Test
    void accessors_shouldAllowNullTotalPoints_whenNoSprintTracksPoints() {
        final PiCapacityResult result = new PiCapacityResult(45.0, 36.0, null, 4, 0);

        assertThat(result.totalPoints()).isNull();
    }
}
