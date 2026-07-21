package fr.pivot.agilite.capacity.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires pour le record {@link EventCapacityResult} (E11 — capacity planning).
 */
class EventCapacityResultTest {

    @Test
    void accessors_shouldReturnConstructedValues() {
        final List<MemberCapacityResult> members = List.of(
                new MemberCapacityResult("m-1", 0.8, 1.0, 9.0, 7.2, 10.8, 6.12));

        final EventCapacityResult result = new EventCapacityResult(
                10, members, 9.0, 7.2, 10.8, 6.12, 1.1, 0.9);

        assertThat(result.totalWorkingDays()).isEqualTo(10);
        assertThat(result.members()).isEqualTo(members);
        assertThat(result.totalJoursHommeNets()).isEqualTo(9.0);
        assertThat(result.totalCapaciteNette()).isEqualTo(7.2);
        assertThat(result.totalPoints()).isEqualTo(10.8);
        assertThat(result.totalEngagementRecommande()).isEqualTo(6.12);
        assertThat(result.loadRatio()).isEqualTo(1.1);
        assertThat(result.predictability()).isEqualTo(0.9);
    }

    @Test
    void accessors_shouldAllowNullRatios_whenOperandsUnavailable() {
        final EventCapacityResult result = new EventCapacityResult(
                10, List.of(), 9.0, 7.2, null, 6.12, null, null);

        assertThat(result.totalPoints()).isNull();
        assertThat(result.loadRatio()).isNull();
        assertThat(result.predictability()).isNull();
    }
}
