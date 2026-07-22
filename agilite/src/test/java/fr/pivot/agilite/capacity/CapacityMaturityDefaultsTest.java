package fr.pivot.agilite.capacity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CapacityMaturityDefaults} — pure, no Spring/database context (US11.6.4).
 */
class CapacityMaturityDefaultsTest {

    @Test
    void forMaturity_null_returnsGlobalDefault() {
        CapacityMaturityDefaults.Defaults defaults = CapacityMaturityDefaults.forMaturity(null);

        assertThat(defaults.focusFactorPercent()).isEqualTo(70);
        assertThat(defaults.marginPercent()).isEqualTo(15);
    }

    @Test
    void forMaturity_forming_returns60_20() {
        CapacityMaturityDefaults.Defaults defaults = CapacityMaturityDefaults.forMaturity(CapacityMaturityLevel.FORMING);

        assertThat(defaults.focusFactorPercent()).isEqualTo(60);
        assertThat(defaults.marginPercent()).isEqualTo(20);
    }

    @Test
    void forMaturity_norming_returns70_10() {
        CapacityMaturityDefaults.Defaults defaults = CapacityMaturityDefaults.forMaturity(CapacityMaturityLevel.NORMING);

        assertThat(defaults.focusFactorPercent()).isEqualTo(70);
        assertThat(defaults.marginPercent()).isEqualTo(10);
    }

    @Test
    void forMaturity_performing_returns80_5() {
        CapacityMaturityDefaults.Defaults defaults = CapacityMaturityDefaults.forMaturity(CapacityMaturityLevel.PERFORMING);

        assertThat(defaults.focusFactorPercent()).isEqualTo(80);
        assertThat(defaults.marginPercent()).isEqualTo(5);
    }
}
