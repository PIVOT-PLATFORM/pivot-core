package fr.pivot.agilite.capacity.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link MemberCapacityResult} (E11 — capacity planning).
 */
class MemberCapacityResultTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final MemberCapacityResult result = new MemberCapacityResult("m-1", 0.8, 1.0, 9.0, 7.2, 10.8, 6.12);

        assertThat(result.memberId()).isEqualTo("m-1");
        assertThat(result.effectiveFocus()).isEqualTo(0.8);
        assertThat(result.absentWorkingDays()).isEqualTo(1.0);
        assertThat(result.joursHommeNets()).isEqualTo(9.0);
        assertThat(result.capaciteNette()).isEqualTo(7.2);
        assertThat(result.points()).isEqualTo(10.8);
        assertThat(result.engagementRecommande()).isEqualTo(6.12);
    }

    @Test
    void accessors_shouldAllowNullPoints_whenEventHasNoPointsPerDay() {
        final MemberCapacityResult result = new MemberCapacityResult("m-2", 0.8, 0.0, 10.0, 8.0, null, 6.8);

        assertThat(result.points()).isNull();
    }
}
